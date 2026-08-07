"use strict";

// ── Pure, side-effect-free server helpers ─────────────────────────────────────
// Extracted from index.js so they can be unit-tested with the Node built-in test
// runner without booting the HTTP server, Firebase Admin, or the Firestore
// listeners. index.js requires these exact implementations, so the tests cover
// the real code path — not a copy that could drift.

const crypto = require("crypto");

// Human-readable push body for a message document, keyed by its `type`.
function notificationBody(data) {
  if (!data || typeof data !== "object") return "New encrypted message";
  if (data.type === "image") return "Sent a photo 🖼";
  if (data.type === "video") return "Sent a video 🎬";
  if (data.type === "voice") return "Sent a voice note 🎙";
  if (data.type === "contact") return "Shared a contact card 📇";
  return "New encrypted message";
}

// Constant-time token comparison so an attacker cannot learn the admin token
// byte-by-byte through response-timing differences. Returns false for any
// length mismatch (timingSafeEqual throws on unequal lengths).
function safeTokenEqual(a, b) {
  const bufA = Buffer.from(String(a));
  const bufB = Buffer.from(String(b));
  if (bufA.length !== bufB.length) return false;
  return crypto.timingSafeEqual(bufA, bufB);
}

// Whitelist for admin-supplied UIDs: printable, bounded length, and free of path
// separators / control characters that could be used to traverse Firestore
// document paths or smuggle control bytes.
function validAdminUid(uid) {
  return typeof uid === "string"
    && uid.length >= 1
    && uid.length <= 128
    && !/[\/\\\u0000-\u001f]/.test(uid);
}

// Parse a single cookie value out of a Cookie header string. Accepts either a
// raw header string or a request-like object exposing `headers.cookie`.
function getCookie(reqOrHeader, name) {
  const raw = typeof reqOrHeader === "string"
    ? reqOrHeader
    : (reqOrHeader && reqOrHeader.headers && reqOrHeader.headers.cookie) || "";
  for (const part of raw.split(";")) {
    const separator = part.indexOf("=");
    if (separator < 0) continue;
    const key = part.slice(0, separator).trim();
    if (key === name) return decodeURIComponent(part.slice(separator + 1).trim());
  }
  return "";
}

// SSRF guard — two-layer defence.
//
// Layer 1 (string predicate — pure, synchronous, testable):
//   isBlockedPreviewHost(hostname) — blocks by name/literal before any I/O.
//   Applied before the DNS lookup so obviously-internal hostnames are rejected
//   without spending a network round trip.
//
// Layer 2 (DNS-resolving — async, authoritative):
//   resolveAndCheckHost(hostname, dnsLookup) — resolves the hostname and
//   checks EVERY returned address against the private/loopback ranges.
//   This is the S04-H1 fix: a hostname whose name passes the string check
//   but resolves to an RFC-1918 or loopback address is blocked at this layer.
//   The caller must use the resolved address for the actual connection
//   (via the `family` + `host` override on the fetch option) so the name
//   cannot be re-resolved between check and use (DNS rebinding).
//
// Applied to the initial URL AND to every redirect hop.

// ── Layer 1: synchronous string predicate ────────────────────────────────────

function isBlockedPreviewHost(hostname) {
  const host = String(hostname || "").toLowerCase().replace(/^\[|\]$/g, ""); // strip IPv6 brackets
  if (!host) return true; // empty → block

  // IPv6 literals
  if (host === "::1" || host === "0:0:0:0:0:0:0:1") return true;
  // IPv4-mapped IPv6: ::ffff:127.0.0.1 or ::ffff:7f00:1
  if (/^::ffff:/.test(host)) {
    const v4part = host.slice(7);
    // ::ffff:127.0.0.1, ::ffff:10.x.x.x, ::ffff:192.168.x.x, ::ffff:172.{16-31}.x.x
    if (isBlockedIPv4(v4part)) return true;
    // hex notation: ::ffff:7f00:0001 (127.0.0.1) or ::ffff:c0a8:0001 (192.168.0.1)
    if (/^[0-9a-f]{4}:[0-9a-f]{4}$/.test(v4part)) {
      const [hi, lo] = v4part.split(":").map(h => parseInt(h, 16));
      const a = (hi >> 8) & 0xff, b = hi & 0xff;
      if (a === 127 || a === 10 || (a === 192 && b === 168) ||
          (a === 172 && b >= 16 && b <= 31) || (a === 169 && b === 254)) return true;
    }
  }
  // fc00::/7 — ULA (Unique Local Address)
  if (/^f[cd][0-9a-f]{2}:/.test(host)) return true;
  // fe80::/10 — link-local IPv6
  if (/^fe[89ab][0-9a-f]:/.test(host)) return true;

  // IPv4 literals
  if (isBlockedIPv4(host)) return true;

  // Well-known dangerous names
  if (host === "localhost") return true;
  if (host === "metadata.google.internal") return true;
  if (host.endsWith(".internal")) return true;
  if (host.endsWith(".local")) return true;
  if (host.endsWith(".localhost")) return true;
  if (host === "169.254.169.254") return true;  // already caught by isBlockedIPv4 but explicit

  return false;
}

function isBlockedIPv4(addr) {
  // Only evaluate dotted-decimal; do not call this on hostnames.
  const parts = addr.split(".");
  if (parts.length !== 4) return false;
  const [a, b] = parts.map(Number);
  if (parts.some(p => !Number.isInteger(Number(p)) || Number(p) < 0 || Number(p) > 255)) return false;
  return (
    a === 127 ||                              // 127.0.0.0/8   loopback
    a === 10 ||                               // 10.0.0.0/8    RFC-1918
    a === 0 ||                                // 0.0.0.0/8     "this" network
    (a === 192 && b === 168) ||               // 192.168.0.0/16 RFC-1918
    (a === 172 && b >= 16 && b <= 31) ||      // 172.16.0.0/12  RFC-1918
    (a === 169 && b === 254) ||               // 169.254.0.0/16 link-local / IMDS
    (a === 100 && b >= 64 && b <= 127) ||     // 100.64.0.0/10  CGNAT
    a === 198 && b === 18 ||                  // 198.18.0.0/15  benchmark
    a === 198 && b === 19 ||
    (a === 240) ||                            // 240.0.0.0/4    reserved
    a === 255                                 // 255.255.255.255 broadcast
  );
}

// ── Layer 2: DNS-resolving predicate (async) ──────────────────────────────────
//
// S04-H1 FIX: resolves the hostname to all its addresses and blocks the request
// if ANY resolved address falls in a private/loopback range.  This closes the
// DNS rebinding / split-horizon attack vector that the string predicate alone
// cannot address: a hostname whose TXT/A record is controlled by an attacker
// can be made to return an internal address, passing the string check.
//
// The caller (fetchFollowingSafeRedirects in index.js) must:
//   1. Call resolveAndCheckHost(hostname, dns.promises.lookup) BEFORE fetching.
//   2. Use the returned {address, family} to pin the connection (via the
//      `lookup` option on http.request or the equivalent) so the runtime
//      does not re-resolve the name between check and connection.
//
// dnsLookup: a function with the signature of dns.promises.lookup(hostname, options)
//   that returns { address: string, family: number }.
//   Accept an injectable for testability.
//
// Returns { ok: true, address, family } or { ok: false, reason: string }.
async function resolveAndCheckHost(hostname, dnsLookup) {
  const host = String(hostname || "").toLowerCase().replace(/^\[|\]$/g, "");
  // Layer-1 string check first — cheap.
  if (isBlockedPreviewHost(host)) {
    return { ok: false, reason: `Blocked by name: ${host}` };
  }

  let resolved;
  try {
    // all: true returns every address (IPv4 + IPv6); family: 0 means "any".
    const results = await dnsLookup(hostname, { all: true, family: 0 });
    resolved = Array.isArray(results) ? results : [results];
  } catch (e) {
    return { ok: false, reason: `DNS resolution failed: ${e.message}` };
  }

  if (!resolved || resolved.length === 0) {
    return { ok: false, reason: "DNS resolution returned no addresses" };
  }

  for (const { address, family } of resolved) {
    const addrStr = String(address || "").toLowerCase();
    if (isBlockedPreviewHost(addrStr)) {
      return { ok: false, reason: `Resolved address blocked: ${addrStr}` };
    }
  }

  // Return the first resolved address so the caller can pin the connection.
  return { ok: true, address: resolved[0].address, family: resolved[0].family };
}

// ── Fixed-window rate-limit evaluation (pure) ─────────────────────────────────
// Given the caller's current record ({ count, windowStart } | undefined) and the
// clock, decide whether the request is allowed and return the record to persist.
// The caller owns storage (a Map); this only holds the windowing math so it can
// be tested deterministically.
function evaluateFixedWindow(rec, now, windowMs, max) {
  if (!rec || now - rec.windowStart >= windowMs) {
    return { allowed: true, record: { count: 1, windowStart: now } };
  }
  if (rec.count >= max) {
    return { allowed: false, record: rec };
  }
  return { allowed: true, record: { count: rec.count + 1, windowStart: rec.windowStart } };
}

// ── Backblaze B2 (S3-compatible) SigV4 presign ────────────────────────────────
// The signing key derivation and canonical-request construction, parameterised so
// they are deterministic and testable (index.js supplies env credentials and the
// current time). Returns null when credentials are absent.
function b2HmacKey(appKey, dateStamp, region) {
  const kDate    = crypto.createHmac("sha256", Buffer.from("AWS4" + appKey)).update(dateStamp).digest();
  const kRegion  = crypto.createHmac("sha256", kDate).update(region).digest();
  const kService = crypto.createHmac("sha256", kRegion).update("s3").digest();
  return crypto.createHmac("sha256", kService).update("aws4_request").digest();
}

function buildB2PresignUrl({ keyId, appKey, bucket, region, method, objectKey, contentType, ttlSeconds, now }) {
  if (!keyId || !appKey) return null;

  const host = "s3." + region + ".backblazeb2.com";
  const clock = now instanceof Date ? now : new Date();
  const ds = clock.toISOString().slice(0, 10).replace(/-/g, "");
  // yyyyMMddTHHmmssZ
  const az = clock.toISOString().replace(/[-:]/g, "").replace(/\.\d{3}/, "");
  const cs = ds + "/" + region + "/s3/aws4_request";
  const cred = keyId + "/" + cs;
  const sh = (method === "PUT" && contentType) ? "content-type;host" : "host";

  const qpRaw = [
    ["X-Amz-Algorithm",     "AWS4-HMAC-SHA256"],
    ["X-Amz-Credential",    cred],
    ["X-Amz-Date",          az],
    ["X-Amz-Expires",       String(ttlSeconds)],
    ["X-Amz-SignedHeaders", sh],
  ];
  const canonQs = qpRaw.slice().sort((a, b) => a[0].localeCompare(b[0]))
    .map(([k, v]) => encodeURIComponent(k) + "=" + encodeURIComponent(v))
    .join("&");

  const ch = (method === "PUT" && contentType)
    ? "content-type:" + contentType + "\nhost:" + host + "\n"
    : "host:" + host + "\n";

  const cr = [method, "/" + bucket + "/" + objectKey, canonQs, ch, sh, "UNSIGNED-PAYLOAD"].join("\n");
  const sts = ["AWS4-HMAC-SHA256", az, cs,
    crypto.createHash("sha256").update(cr).digest("hex")].join("\n");
  const sk = b2HmacKey(appKey, ds, region);
  const sig = crypto.createHmac("sha256", sk).update(sts).digest("hex");

  return "https://" + host + "/" + bucket + "/" + objectKey
    + "?" + canonQs + "&X-Amz-Signature=" + sig;
}

module.exports = {
  notificationBody,
  safeTokenEqual,
  validAdminUid,
  getCookie,
  isBlockedPreviewHost,
  isBlockedIPv4,
  resolveAndCheckHost,
  evaluateFixedWindow,
  b2HmacKey,
  buildB2PresignUrl,
};
