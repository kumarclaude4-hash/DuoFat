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

// SSRF guard: block loopback, RFC-1918 private ranges, link-local, and cloud
// metadata hostnames. Applied to the initial /linkPreview target AND to every
// redirect hop, so a public host cannot bounce the fetch to an internal address.
function isBlockedPreviewHost(hostname) {
  const host = String(hostname || "").toLowerCase();
  return /^(localhost|127\.|10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|\[?::1\]?)/.test(host) ||
    host === "metadata.google.internal" ||
    host === "169.254.169.254" ||
    host.endsWith(".internal") ||
    host.endsWith(".local");
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
  evaluateFixedWindow,
  b2HmacKey,
  buildB2PresignUrl,
};
