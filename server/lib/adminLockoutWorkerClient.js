"use strict";

// ── Admin lockout Worker client (Cloudflare Durable Object RPC) ─────────────
//
// FOLLOW-UP FIX (2026-08-14, same day as the original KV migration): the
// first Cloudflare KV version of the admin lockout counter
// (`cloudflareKvStore.js`, since removed) called Cloudflare's KV REST API
// directly from this Node process with a non-atomic GET → increment → PUT.
// That was correctly flagged as a real concurrency bug for a brute-force
// counter, not an acceptable "KV doesn't have INCR" tradeoff: concurrent
// failed admin-login attempts could undercount and let an attacker exceed
// the intended guess budget.
//
// This module replaces that direct-KV client. It no longer talks to
// Cloudflare's KV REST API at all — instead it calls ONE HTTP endpoint
// (`POST <workerUrl>/adminLockout`) on the project's existing Cloudflare
// Worker (`/worker`, already deployed for tiered media storage), which
// forwards the request to a Durable Object
// (`worker/src/adminLockoutDurableObject.js`) keyed on the normalized IP.
// Durable Objects guarantee that all requests to the same instance are
// processed strictly one at a time, so the increment-and-check the Durable
// Object performs is genuinely atomic — see that file's header comment for
// the full explanation of the guarantee and why it closes the race this
// client's predecessor had.
//
// This is a THIN client: the atomic decision-making (increment, window
// check, lock check) all happens inside the Durable Object. This module's
// job is only to make the authenticated HTTP call and turn the response
// into a plain `{ count, locked }` (or throw). It intentionally does not
// reach for a Cloudflare SDK — the surface used is one POST with one auth
// header, which does not justify a dependency. Uses Node's built-in `fetch`/
// `AbortController` (Node 18+; this project targets Node 20 — see
// package.json `engines`), so this adds zero new npm dependencies.
//
// Error handling contract:
//   - `recordFailure`/`getStatus`/`reset` all resolve normally on any 2xx
//     response with a well-formed JSON body, and throw
//     `AdminLockoutWorkerError` for every other outcome: network failure,
//     timeout, non-2xx status, or a malformed/unexpected response body.
//     There is no "absent key" ambiguity to resolve here (unlike the old KV
//     client) — the Worker/Durable Object always returns a definitive
//     `{ count: 0, locked: false }` for a key it has never seen, so every
//     failure mode is unambiguously "the call itself failed", which the
//     caller (`adminLockoutStore.js`) treats as fail-safe-to-local-fallback,
//     exactly as it did for the old KV client's thrown errors.
//
// Timeouts: every request is bounded by `timeoutMs` (default 5s) via
// `AbortController`, matching the old KV client's fail-safe latency bound —
// a stalled Worker/Durable Object call cannot hang an admin login/lockout
// check indefinitely.
//
// Secrets: `workerSecret` is only ever placed in the `Authorization` request
// header, which this module never logs. Thrown error messages are built
// from the Worker's own JSON error envelope (`{ error: "..." }`) or a
// truncated response body — never from the request itself — so an error
// can never echo back the secret.

const DEFAULT_TIMEOUT_MS = 5000;
const MAX_ERROR_BODY_CHARS = 300;

class AdminLockoutWorkerError extends Error {
  constructor(message, { status, cause } = {}) {
    super(message);
    this.name = "AdminLockoutWorkerError";
    if (status !== undefined) this.status = status;
    if (cause !== undefined) this.cause = cause;
  }
}

// Truncates and returns a safe-to-surface description of a non-2xx or
// malformed response body. Never includes request headers/secrets — those
// are never present in a response body to begin with.
async function describeErrorBody(res) {
  let text;
  try {
    text = await res.text();
  } catch (err) {
    return `<unreadable response body: ${err.message}>`;
  }
  try {
    const json = JSON.parse(text);
    if (json && typeof json.error === "string") return json.error;
  } catch {
    // Not JSON — fall through to the raw (truncated) text below.
  }
  return text.length > MAX_ERROR_BODY_CHARS ? `${text.slice(0, MAX_ERROR_BODY_CHARS)}…` : text;
}

/**
 * Creates a client for the admin lockout Worker endpoint.
 *
 * @param {object} opts
 * @param {string} opts.workerUrl - Base URL of the deployed Worker, e.g.
 *   `https://duoshield-storage.<subdomain>.workers.dev` or a custom domain.
 *   `/adminLockout` is appended; any trailing slash on `workerUrl` is
 *   tolerated.
 * @param {string} opts.workerSecret - Shared secret matching the Worker's
 *   `ADMIN_LOCKOUT_SECRET` (see worker/wrangler.jsonc and
 *   worker/.dev.vars.template for how to generate/set it).
 * @param {number} [opts.timeoutMs] - Per-request abort timeout.
 * @param {typeof fetch} [opts.fetchImpl] - Injectable for tests; defaults to
 *   the global `fetch`.
 */
function createAdminLockoutWorkerClient({ workerUrl, workerSecret, timeoutMs = DEFAULT_TIMEOUT_MS, fetchImpl } = {}) {
  if (!workerUrl || !workerSecret) {
    throw new Error("createAdminLockoutWorkerClient requires workerUrl and workerSecret");
  }
  const doFetch = fetchImpl || globalThis.fetch;
  if (typeof doFetch !== "function") {
    throw new Error("createAdminLockoutWorkerClient: no fetch implementation available (pass opts.fetchImpl)");
  }
  const endpoint = `${workerUrl.replace(/\/+$/, "")}/adminLockout`;

  async function call(action, { key, windowMs, maxFails } = {}) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    let res;
    try {
      res = await doFetch(endpoint, {
        method: "POST",
        headers: {
          // Never logged — see module header comment.
          Authorization: `Bearer ${workerSecret}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ action, key, windowMs, maxFails }),
        signal: controller.signal,
      });
    } catch (err) {
      if (err && err.name === "AbortError") {
        throw new AdminLockoutWorkerError(`Admin lockout Worker ${action} timed out after ${timeoutMs}ms`, { cause: err });
      }
      throw new AdminLockoutWorkerError(`Admin lockout Worker ${action} request failed: ${err.message}`, { cause: err });
    } finally {
      clearTimeout(timer);
    }

    if (!res.ok) {
      throw new AdminLockoutWorkerError(`Admin lockout Worker ${action} failed (${res.status}): ${await describeErrorBody(res)}`, {
        status: res.status,
      });
    }

    let json;
    try {
      json = await res.json();
    } catch (err) {
      throw new AdminLockoutWorkerError(`Admin lockout Worker ${action} returned an unreadable body: ${err.message}`, { cause: err });
    }
    if (!json || typeof json !== "object") {
      throw new AdminLockoutWorkerError(`Admin lockout Worker ${action} returned a malformed response`);
    }
    return json;
  }

  return {
    // Atomic increment-and-check, performed inside the Durable Object.
    // Resolves to `{ count, locked }`.
    async recordFailure(key, { windowMs, maxFails } = {}) {
      const json = await call("record", { key, windowMs, maxFails });
      if (typeof json.count !== "number" || typeof json.locked !== "boolean") {
        throw new AdminLockoutWorkerError("Admin lockout Worker record returned a malformed response");
      }
      return { count: json.count, locked: json.locked };
    },

    // Read-only status check. Resolves to `{ count, locked }`.
    async getStatus(key, { windowMs, maxFails } = {}) {
      const json = await call("status", { key, windowMs, maxFails });
      if (typeof json.count !== "number" || typeof json.locked !== "boolean") {
        throw new AdminLockoutWorkerError("Admin lockout Worker status returned a malformed response");
      }
      return { count: json.count, locked: json.locked };
    },

    // Idempotent — resetting an already-clear key is a no-op success,
    // matching the old KV/Redis clients' DELETE semantics.
    async reset(key) {
      await call("reset", { key });
    },
  };
}

module.exports = {
  createAdminLockoutWorkerClient,
  AdminLockoutWorkerError,
};
