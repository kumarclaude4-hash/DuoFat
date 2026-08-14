"use strict";

// ── Cloudflare Workers KV REST client (Upstash → Cloudflare KV migration) ────
//
// This is a deliberately small, dependency-free client for the three KV
// operations the application actually needs (GET/PUT/DELETE a single key's
// value), talking directly to Cloudflare's HTTP API from this Node process
// (no Worker/Durable Object in the request path — see adminLockoutStore.js's
// header comment for why that tradeoff was accepted rather than built
// around). It intentionally does not pull in a Cloudflare SDK: the surface
// used is three HTTP calls with one auth header, which does not justify a
// dependency.
//
// Uses Node's built-in `fetch`/`AbortController` (available since Node 18;
// this project targets Node 20 — see package.json `engines`), so this adds
// zero new npm dependencies.
//
// Cloudflare KV vs. Redis — what does NOT map:
//   - No INCR, no atomic counter, no CAS write. GET/PUT/DELETE only.
//   - No Lua/EVAL equivalent, so the "atomic increment + conditional TTL"
//     trick adminLockoutStore.js relied on has no direct replacement here.
//     Callers that need that semantics must implement their own
//     read-modify-write and accept (and document) the resulting race window
//     — this client does not paper over that; it only provides GET/PUT/DEL.
//   - TTL has a hard minimum: Cloudflare rejects `expiration_ttl` below 60
//     seconds. This client clamps up to that floor rather than erroring, on
//     the principle that KV's TTL is a cleanup backstop, not the source of
//     truth for "is this record still valid" — callers must make that call
//     themselves from data in the record (e.g. a stored timestamp), the same
//     way adminLockoutStore.js does.
//
// Error handling contract:
//   - `get(key)` resolves to `null` for a genuinely absent key (HTTP 404 on
//     Cloudflare's read-value endpoint) and only THROWS `CloudflareKvError`
//     for an actual failure (network error, timeout, non-404 non-2xx, or a
//     malformed/unexpected response body). Callers must be able to tell
///     "this key has never failed" apart from "Cloudflare is unreachable
//     right now" — collapsing those two into the same return value would
//     silently turn a Cloudflare outage into "nobody is locked out".
//   - `put`/`delete` throw `CloudflareKvError` on any non-2xx status AND on a
//     200 response whose JSON envelope says `{ success: false }` (Cloudflare
//     returns 200 with a false `success` for some validation failures, not
//     just non-2xx statuses).
//
// Timeouts: every request is bounded by `timeoutMs` (default 5s) via
// `AbortController`, so a stalled Cloudflare API call cannot hang an admin
// login/lockout check indefinitely — the caller's fail-safe fallback (see
// adminLockoutStore.js) depends on failures surfacing promptly.
//
// Secrets: the API token is only ever placed in the `Authorization` request
// header, which this module never logs. Error messages are built from
// Cloudflare's own JSON error envelope or a truncated response body — never
// from the request itself — so a thrown error can never echo back the
// token. Do not add request/response logging to this file without
// preserving that property.

const DEFAULT_TIMEOUT_MS = 5000;
const MIN_TTL_SECONDS = 60; // Cloudflare's floor for expiration_ttl.
const MAX_ERROR_BODY_CHARS = 300; // Bound how much of an unexpected body we ever surface.

class CloudflareKvError extends Error {
  constructor(message, { status, cause } = {}) {
    super(message);
    this.name = "CloudflareKvError";
    if (status !== undefined) this.status = status;
    if (cause !== undefined) this.cause = cause;
  }
}

function buildUrl({ accountId, namespaceId, key }) {
  return (
    `https://api.cloudflare.com/client/v4/accounts/${encodeURIComponent(accountId)}` +
    `/storage/kv/namespaces/${encodeURIComponent(namespaceId)}/values/${encodeURIComponent(key)}`
  );
}

// Reads the response body once and returns a short, safe-to-surface summary
// of a Cloudflare error envelope (`{ success:false, errors:[{code,message}] }`)
// if present, otherwise a truncated raw body. Never includes request
// headers/tokens — those are not present in any response body to begin with.
async function describeErrorBody(res) {
  let text;
  try {
    text = await res.text();
  } catch (err) {
    return `<unreadable response body: ${err.message}>`;
  }
  try {
    const json = JSON.parse(text);
    if (json && Array.isArray(json.errors) && json.errors.length > 0) {
      return json.errors.map((e) => (e && e.message ? e.message : String(e && e.code))).join("; ");
    }
  } catch {
    // Not JSON — fall through to the raw (truncated) text below.
  }
  return text.length > MAX_ERROR_BODY_CHARS ? `${text.slice(0, MAX_ERROR_BODY_CHARS)}…` : text;
}

// Reads the body once as text and, if it parses as JSON, returns the parsed
// value; otherwise returns null. Used for the write/delete envelope, which
// is JSON on success (`{ success:true, ... }`) — a malformed/non-JSON body
// on a 2xx is itself treated as a failure by the caller.
async function readJsonOrNull(res) {
  let text;
  try {
    text = await res.text();
  } catch {
    return null;
  }
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

function summarizeEnvelopeErrors(json) {
  if (json && Array.isArray(json.errors) && json.errors.length > 0) {
    return json.errors.map((e) => (e && e.message ? e.message : String(e && e.code))).join("; ");
  }
  return "no error detail in response";
}

/**
 * Creates a small client for Cloudflare Workers KV's per-key REST endpoints.
 *
 * @param {object} opts
 * @param {string} opts.accountId - Cloudflare account ID.
 * @param {string} opts.apiToken - Cloudflare API token, scoped to KV
 *   read/write/delete on `opts.namespaceId` only (least privilege — see
 *   server/README.md for the exact token permission to create).
 * @param {string} opts.namespaceId - Target KV namespace ID.
 * @param {number} [opts.timeoutMs] - Per-request abort timeout.
 * @param {typeof fetch} [opts.fetchImpl] - Injectable for tests; defaults to
 *   the global `fetch`.
 */
function createCloudflareKvClient({ accountId, apiToken, namespaceId, timeoutMs = DEFAULT_TIMEOUT_MS, fetchImpl } = {}) {
  if (!accountId || !apiToken || !namespaceId) {
    throw new Error("createCloudflareKvClient requires accountId, apiToken, and namespaceId");
  }
  const doFetch = fetchImpl || globalThis.fetch;
  if (typeof doFetch !== "function") {
    throw new Error("createCloudflareKvClient: no fetch implementation available (pass opts.fetchImpl)");
  }

  async function send(method, key, { body, expirationTtl } = {}) {
    const url = new URL(buildUrl({ accountId, namespaceId, key }));
    if (expirationTtl !== undefined) {
      url.searchParams.set("expiration_ttl", String(Math.max(MIN_TTL_SECONDS, Math.ceil(expirationTtl))));
    }
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      return await doFetch(url, {
        method,
        headers: {
          // Never logged — see module header comment.
          Authorization: `Bearer ${apiToken}`,
          ...(body !== undefined ? { "Content-Type": "text/plain; charset=utf-8" } : {}),
        },
        body,
        signal: controller.signal,
      });
    } catch (err) {
      if (err && err.name === "AbortError") {
        throw new CloudflareKvError(`Cloudflare KV ${method} timed out after ${timeoutMs}ms`, { cause: err });
      }
      throw new CloudflareKvError(`Cloudflare KV ${method} request failed: ${err.message}`, { cause: err });
    } finally {
      clearTimeout(timer);
    }
  }

  return {
    // Resolves to the raw string value, or `null` if the key does not exist.
    // Throws CloudflareKvError for every other failure mode (see contract
    // above) so "absent" and "failed" are never confused by a caller.
    async get(key) {
      const res = await send("GET", key);
      if (res.status === 404) return null;
      if (!res.ok) {
        throw new CloudflareKvError(`Cloudflare KV GET failed (${res.status}): ${await describeErrorBody(res)}`, {
          status: res.status,
        });
      }
      try {
        return await res.text();
      } catch (err) {
        throw new CloudflareKvError(`Cloudflare KV GET returned an unreadable body: ${err.message}`, { cause: err });
      }
    },

    // `expirationTtl` is in seconds and is clamped up to Cloudflare's 60s
    // floor if smaller — see module header comment for why that's safe here.
    async put(key, value, { expirationTtl } = {}) {
      const res = await send("PUT", key, { body: value, expirationTtl });
      if (!res.ok) {
        throw new CloudflareKvError(`Cloudflare KV PUT failed (${res.status}): ${await describeErrorBody(res)}`, {
          status: res.status,
        });
      }
      const json = await readJsonOrNull(res);
      if (!json || json.success !== true) {
        throw new CloudflareKvError(`Cloudflare KV PUT returned an unexpected response: ${summarizeEnvelopeErrors(json)}`);
      }
    },

    // Deleting an already-absent key is treated as success (matches Redis
    // `DEL`'s idempotent semantics, which adminLockoutStore.js's reset() and
    // its tests rely on).
    async delete(key) {
      const res = await send("DELETE", key);
      if (res.status === 404) return;
      if (!res.ok) {
        throw new CloudflareKvError(`Cloudflare KV DELETE failed (${res.status}): ${await describeErrorBody(res)}`, {
          status: res.status,
        });
      }
      const json = await readJsonOrNull(res);
      if (!json || json.success !== true) {
        throw new CloudflareKvError(`Cloudflare KV DELETE returned an unexpected response: ${summarizeEnvelopeErrors(json)}`);
      }
    },
  };
}

module.exports = {
  createCloudflareKvClient,
  CloudflareKvError,
  MIN_TTL_SECONDS,
};
