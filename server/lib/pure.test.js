"use strict";

// Unit tests for the pure server helpers extracted from index.js.
// Run with: npm test   (inside server/) — uses the Node built-in test runner.

const test = require("node:test");
const assert = require("node:assert/strict");
const crypto = require("crypto");
const pure = require("./pure");

test("notificationBody maps known types and falls back safely", () => {
  assert.equal(pure.notificationBody({ type: "image" }), "Sent a photo 🖼");
  assert.equal(pure.notificationBody({ type: "video" }), "Sent a video 🎬");
  assert.equal(pure.notificationBody({ type: "voice" }), "Sent a voice note 🎙");
  assert.equal(pure.notificationBody({ type: "contact" }), "Shared a contact card 📇");
  assert.equal(pure.notificationBody({ type: "text" }), "New encrypted message");
  assert.equal(pure.notificationBody({}), "New encrypted message");
  // Robust against non-objects (never throws on malformed input).
  assert.equal(pure.notificationBody(null), "New encrypted message");
  assert.equal(pure.notificationBody(undefined), "New encrypted message");
  assert.equal(pure.notificationBody("image"), "New encrypted message");
});

test("safeTokenEqual is correct and rejects length mismatches", () => {
  assert.equal(pure.safeTokenEqual("s3cr3t-token", "s3cr3t-token"), true);
  assert.equal(pure.safeTokenEqual("s3cr3t-token", "s3cr3t-tokeX"), false);
  // Length mismatch must return false, never throw (timingSafeEqual throws).
  assert.equal(pure.safeTokenEqual("short", "a-much-longer-token"), false);
  assert.equal(pure.safeTokenEqual("", ""), true);
  // Coerces non-strings rather than throwing.
  assert.equal(pure.safeTokenEqual(12345, "12345"), true);
  assert.equal(pure.safeTokenEqual(12345, 12346), false);
});

test("safeTokenEqual accepts an equal-length token containing NUL bytes", () => {
  const a = "abc\u0000def";
  const b = "abc\u0000def";
  assert.equal(pure.safeTokenEqual(a, b), true);
});

test("validAdminUid enforces the UID whitelist", () => {
  assert.equal(pure.validAdminUid("user_123"), true);
  assert.equal(pure.validAdminUid("a"), true);
  assert.equal(pure.validAdminUid("x".repeat(128)), true);
  // Boundaries / rejects.
  assert.equal(pure.validAdminUid(""), false);
  assert.equal(pure.validAdminUid("x".repeat(129)), false);
  assert.equal(pure.validAdminUid("a/b"), false);        // path separator
  assert.equal(pure.validAdminUid("a\\b"), false);       // backslash
  assert.equal(pure.validAdminUid("a\u0000b"), false);   // NUL
  assert.equal(pure.validAdminUid("a\nb"), false);       // control char
  assert.equal(pure.validAdminUid(123), false);          // non-string
  assert.equal(pure.validAdminUid(null), false);
  assert.equal(pure.validAdminUid(undefined), false);
});

test("getCookie parses from a raw header string", () => {
  const header = "theme=dark; admin_session=abc%2F123; last=1";
  assert.equal(pure.getCookie(header, "theme"), "dark");
  // Value is URL-decoded.
  assert.equal(pure.getCookie(header, "admin_session"), "abc/123");
  assert.equal(pure.getCookie(header, "last"), "1");
  assert.equal(pure.getCookie(header, "missing"), "");
});

test("getCookie parses from a request-like object (index.js call shape)", () => {
  const req = { headers: { cookie: "admin_session=tok; foo=bar" } };
  assert.equal(pure.getCookie(req, "admin_session"), "tok");
  assert.equal(pure.getCookie(req, "foo"), "bar");
  // No cookie header at all.
  assert.equal(pure.getCookie({ headers: {} }, "admin_session"), "");
  assert.equal(pure.getCookie({}, "admin_session"), "");
});

test("isBlockedPreviewHost blocks SSRF-prone targets", () => {
  const blocked = [
    "localhost",
    "127.0.0.1",
    "10.0.0.5",
    "192.168.1.10",
    "172.16.0.1",
    "172.31.255.255",
    "169.254.169.254",           // AWS/GCP metadata IP
    "metadata.google.internal",
    "db.internal",
    "printer.local",
    "::1",
    "LOCALHOST",                 // case-insensitive
  ];
  for (const h of blocked) {
    assert.equal(pure.isBlockedPreviewHost(h), true, `expected blocked: ${h}`);
  }
});

test("isBlockedPreviewHost allows legitimate public hosts", () => {
  const allowed = [
    "example.com",
    "www.vercel.com",
    "cdn.jsdelivr.net",
    "172.32.0.1",   // just outside the 172.16-31 private range
    "11.0.0.1",     // not RFC-1918
    "8.8.8.8",
  ];
  for (const h of allowed) {
    assert.equal(pure.isBlockedPreviewHost(h), false, `expected allowed: ${h}`);
  }
  // Never throws on empty/nullish input.
  assert.equal(pure.isBlockedPreviewHost(""), false);
  assert.equal(pure.isBlockedPreviewHost(null), false);
  assert.equal(pure.isBlockedPreviewHost(undefined), false);
});

test("evaluateFixedWindow opens a window on first hit", () => {
  const { allowed, record } = pure.evaluateFixedWindow(undefined, 1000, 60000, 5);
  assert.equal(allowed, true);
  assert.deepEqual(record, { count: 1, windowStart: 1000 });
});

test("evaluateFixedWindow increments within the window until the cap", () => {
  const windowMs = 60000;
  const max = 3;
  let rec = pure.evaluateFixedWindow(undefined, 0, windowMs, max).record;   // 1
  let r2 = pure.evaluateFixedWindow(rec, 10, windowMs, max);                // 2
  assert.equal(r2.allowed, true);
  assert.equal(r2.record.count, 2);
  let r3 = pure.evaluateFixedWindow(r2.record, 20, windowMs, max);          // 3
  assert.equal(r3.allowed, true);
  assert.equal(r3.record.count, 3);
  // 4th within the window is blocked and the record is unchanged.
  let r4 = pure.evaluateFixedWindow(r3.record, 30, windowMs, max);
  assert.equal(r4.allowed, false);
  assert.equal(r4.record.count, 3);
});

test("evaluateFixedWindow resets once the window elapses", () => {
  const windowMs = 60000;
  const max = 2;
  const first = pure.evaluateFixedWindow(undefined, 0, windowMs, max).record;
  const blocked = pure.evaluateFixedWindow(
    { count: max, windowStart: 0 }, 100, windowMs, max
  );
  assert.equal(blocked.allowed, false);
  // At exactly windowMs later, the window rolls over and a new one starts.
  const rolled = pure.evaluateFixedWindow(
    { count: max, windowStart: 0 }, windowMs, windowMs, max
  );
  assert.equal(rolled.allowed, true);
  assert.deepEqual(rolled.record, { count: 1, windowStart: windowMs });
  assert.ok(first);
});

test("buildB2PresignUrl returns null without credentials", () => {
  assert.equal(
    pure.buildB2PresignUrl({
      keyId: "", appKey: "", bucket: "b", region: "eu-central-003",
      method: "GET", objectKey: "k", ttlSeconds: 300, now: new Date(0),
    }),
    null
  );
  assert.equal(
    pure.buildB2PresignUrl({
      keyId: "id", appKey: "", bucket: "b", region: "eu-central-003",
      method: "GET", objectKey: "k", ttlSeconds: 300, now: new Date(0),
    }),
    null
  );
});

test("buildB2PresignUrl produces a deterministic, well-formed SigV4 GET URL", () => {
  const url = pure.buildB2PresignUrl({
    keyId: "000abc0000000000000000001",
    appKey: "K000secretsecretsecretsecret",
    bucket: "my-bucket",
    region: "eu-central-003",
    method: "GET",
    objectKey: "path/to/object.bin",
    ttlSeconds: 900,
    now: new Date("2026-01-02T03:04:05.678Z"),
  });
  assert.ok(url.startsWith("https://s3.eu-central-003.backblazeb2.com/my-bucket/path/to/object.bin?"));
  assert.match(url, /X-Amz-Algorithm=AWS4-HMAC-SHA256/);
  assert.match(url, /X-Amz-Date=20260102T030405Z/);
  assert.match(url, /X-Amz-Expires=900/);
  // GET signs only the host header.
  assert.match(url, /X-Amz-SignedHeaders=host/);
  assert.match(url, /&X-Amz-Signature=[0-9a-f]{64}$/);

  // Deterministic: same inputs → same signature.
  const url2 = pure.buildB2PresignUrl({
    keyId: "000abc0000000000000000001",
    appKey: "K000secretsecretsecretsecret",
    bucket: "my-bucket",
    region: "eu-central-003",
    method: "GET",
    objectKey: "path/to/object.bin",
    ttlSeconds: 900,
    now: new Date("2026-01-02T03:04:05.678Z"),
  });
  assert.equal(url, url2);
});

test("buildB2PresignUrl signs content-type for PUT with a body type", () => {
  const url = pure.buildB2PresignUrl({
    keyId: "000abc0000000000000000001",
    appKey: "K000secretsecretsecretsecret",
    bucket: "my-bucket",
    region: "eu-central-003",
    method: "PUT",
    objectKey: "upload.jpg",
    contentType: "image/jpeg",
    ttlSeconds: 300,
    now: new Date("2026-01-02T03:04:05.678Z"),
  });
  // content-type must be part of the signed headers for a PUT with a type.
  assert.match(url, /X-Amz-SignedHeaders=content-type%3Bhost/);
});

test("buildB2PresignUrl changes the signature when any input changes", () => {
  const base = {
    keyId: "000abc0000000000000000001",
    appKey: "K000secretsecretsecretsecret",
    bucket: "my-bucket",
    region: "eu-central-003",
    method: "GET",
    objectKey: "a.bin",
    ttlSeconds: 900,
    now: new Date("2026-01-02T03:04:05.678Z"),
  };
  const sig = (u) => u.match(/X-Amz-Signature=([0-9a-f]{64})/)[1];
  const baseSig = sig(pure.buildB2PresignUrl(base));
  assert.notEqual(baseSig, sig(pure.buildB2PresignUrl({ ...base, objectKey: "b.bin" })));
  assert.notEqual(baseSig, sig(pure.buildB2PresignUrl({ ...base, ttlSeconds: 901 })));
  assert.notEqual(baseSig, sig(pure.buildB2PresignUrl({ ...base, appKey: "K000different-secret-value00" })));
});

test("b2HmacKey derivation matches a hand-computed AWS4 signing key", () => {
  // Reference computation of the SigV4 signing key chain.
  const appKey = "K000secretsecretsecretsecret";
  const ds = "20260102";
  const region = "eu-central-003";
  const kDate = crypto.createHmac("sha256", Buffer.from("AWS4" + appKey)).update(ds).digest();
  const kRegion = crypto.createHmac("sha256", kDate).update(region).digest();
  const kService = crypto.createHmac("sha256", kRegion).update("s3").digest();
  const expected = crypto.createHmac("sha256", kService).update("aws4_request").digest();
  assert.deepEqual(pure.b2HmacKey(appKey, ds, region), expected);
});
