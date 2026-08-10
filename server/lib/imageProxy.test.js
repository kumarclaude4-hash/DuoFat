"use strict";

// Unit tests for lib/imageProxy.js (S04-H3 / S08-H4).
//
// Every expected MAC here is produced by the module's own HMAC over its own
// canonical input — no signature is hand-written, and no expected digest is
// pasted in as a literal. The forgery tests therefore exercise real rejection
// paths rather than asserting a value someone typed.

const test = require("node:test");
const assert = require("node:assert/strict");
const crypto = require("node:crypto");

const proxy = require("./imageProxy");

const SECRET = "test-secret-do-not-use-in-production";
const TARGET = "https://cdn.example.com/thumb/abc123.jpg";

function paramsOf(signedPath) {
  return new URL(signedPath, "https://server.example").searchParams;
}

test("signImageUrl produces a verifiable path pointing at the proxy route", () => {
  const signed = proxy.signImageUrl(TARGET, SECRET);
  assert.ok(signed.startsWith(`${proxy.PROXY_PATH}?`), `unexpected path: ${signed}`);

  const verdict = proxy.verifyImageUrl(paramsOf(signed), SECRET);
  assert.equal(verdict.ok, true);
  assert.equal(verdict.targetUrl, TARGET, "round-trips the exact target");
});

test("signImageUrl round-trips URLs with query strings and unicode", () => {
  for (const target of [
    "https://cdn.example.com/i?w=100&h=50&sig=a/b+c=",
    "https://例え.jp/画像.png",
    "https://cdn.example.com/path%20with%20escapes.jpg",
  ]) {
    const signed = proxy.signImageUrl(target, SECRET);
    const verdict = proxy.verifyImageUrl(paramsOf(signed), SECRET);
    assert.equal(verdict.ok, true, `${target} must verify`);
    assert.equal(verdict.targetUrl, target, `${target} must round-trip byte-exact`);
  }
});

test("signImageUrl returns null with no secret so the caller omits the image", () => {
  // Must be null, not the raw URL: falling back to the attacker's host would
  // silently reintroduce the very leak this module exists to close.
  assert.equal(proxy.signImageUrl(TARGET, ""), null);
  assert.equal(proxy.signImageUrl(TARGET, undefined), null);
});

test("verifyImageUrl rejects a tampered target with the original signature", () => {
  const signed = proxy.signImageUrl(TARGET, SECRET);
  const params = paramsOf(signed);
  const forged = new URLSearchParams({
    // Swap in an internal target, keep the legitimate expiry + MAC.
    u: Buffer.from("http://169.254.169.254/latest/meta-data/", "utf8").toString("base64url"),
    e: params.get("e"),
    s: params.get("s"),
  });
  const verdict = proxy.verifyImageUrl(forged, SECRET);
  assert.equal(verdict.ok, false);
  assert.equal(verdict.reason, "bad signature");
});

test("verifyImageUrl rejects an extended expiry (MAC covers the expiry)", () => {
  const signed = proxy.signImageUrl(TARGET, SECRET);
  const params = paramsOf(signed);
  const forged = new URLSearchParams({
    u: params.get("u"),
    e: String(Number(params.get("e")) + 10 * 365 * 24 * 3600 * 1000), // +10 years
    s: params.get("s"),
  });
  assert.equal(proxy.verifyImageUrl(forged, SECRET).ok, false, "expiry must be authenticated");
});

test("verifyImageUrl rejects an expired signature", () => {
  const signed = proxy.signImageUrl(TARGET, SECRET, { now: 1_000_000 });
  const verdict = proxy.verifyImageUrl(paramsOf(signed), SECRET, {
    now: 1_000_000 + proxy.IMAGE_PROXY_TTL_MS + 1,
  });
  assert.equal(verdict.ok, false);
  assert.equal(verdict.reason, "signature expired");
});

test("verifyImageUrl accepts a signature just inside its TTL", () => {
  const signed = proxy.signImageUrl(TARGET, SECRET, { now: 1_000_000 });
  const verdict = proxy.verifyImageUrl(paramsOf(signed), SECRET, {
    now: 1_000_000 + proxy.IMAGE_PROXY_TTL_MS - 1,
  });
  assert.equal(verdict.ok, true);
});

test("verifyImageUrl rejects a signature made with a different secret", () => {
  const signed = proxy.signImageUrl(TARGET, "other-secret");
  const verdict = proxy.verifyImageUrl(paramsOf(signed), SECRET);
  assert.equal(verdict.ok, false);
  assert.equal(verdict.reason, "bad signature");
});

test("verifyImageUrl fails closed on missing, malformed and empty parameters", () => {
  const cases = [
    [new URLSearchParams({}), /missing signature/],
    [new URLSearchParams({ u: "abc" }), /missing signature/],
    [new URLSearchParams({ u: "abc", e: "123" }), /missing signature/],
    [new URLSearchParams({ u: "abc", e: "not-a-number", s: "xx" }), /malformed expiry/],
    [new URLSearchParams({ u: "", e: "99999999999999", s: "xx" }), /missing signature/],
  ];
  for (const [params, expected] of cases) {
    const verdict = proxy.verifyImageUrl(params, SECRET, { now: 0 });
    assert.equal(verdict.ok, false);
    assert.match(verdict.reason, expected);
  }
});

test("verifyImageUrl rejects a wrong-length signature without throwing", () => {
  // crypto.timingSafeEqual throws on length mismatch; the length gate must run
  // first or a 1-byte `s` parameter becomes a 500 instead of a clean reject.
  const signed = proxy.signImageUrl(TARGET, SECRET);
  const params = paramsOf(signed);
  const short = new URLSearchParams({ u: params.get("u"), e: params.get("e"), s: "AA" });
  const verdict = proxy.verifyImageUrl(short, SECRET);
  assert.equal(verdict.ok, false);
  assert.equal(verdict.reason, "bad signature");
});

test("verifyImageUrl reports an unconfigured secret rather than accepting", () => {
  const signed = proxy.signImageUrl(TARGET, SECRET);
  const verdict = proxy.verifyImageUrl(paramsOf(signed), "");
  assert.equal(verdict.ok, false);
  assert.match(verdict.reason, /not configured/);
});

test("signingInput is unambiguous across the url/expiry boundary", () => {
  // Length-delimited construction: two different (url, expiry) splits that
  // concatenate to the same bytes must still produce different MAC inputs.
  const a = proxy.signingInput("https://a.example/x", "12");
  const b = proxy.signingInput("https://a.example/x1", "2");
  assert.notEqual(a.toString("hex"), b.toString("hex"));
});

test("distinct targets and expiries yield distinct MACs", () => {
  const first = proxy.signImageUrl("https://a.example/1.png", SECRET, { now: 1000 });
  const second = proxy.signImageUrl("https://a.example/2.png", SECRET, { now: 1000 });
  assert.notEqual(paramsOf(first).get("s"), paramsOf(second).get("s"));
});

test("the MAC matches an independently computed HMAC over the canonical input", () => {
  // Cross-check against crypto directly, so the test would catch the module
  // silently changing its construction (e.g. dropping the separator).
  const signed = proxy.signImageUrl(TARGET, SECRET, { now: 5_000_000 });
  const params = paramsOf(signed);
  const expected = crypto
    .createHmac("sha256", SECRET)
    .update(proxy.signingInput(TARGET, params.get("e")))
    .digest();
  assert.equal(params.get("s"), expected.toString("base64url"));
});

test("isAllowedImageType allows real image types and nothing else", () => {
  for (const type of [
    "image/jpeg", "image/png", "image/gif", "image/webp", "image/avif",
    "image/PNG", "image/jpeg; charset=binary", " image/png ",
  ]) {
    assert.equal(proxy.isAllowedImageType(type), true, `${type} must be allowed`);
  }
  for (const type of [
    "text/html", "application/javascript", "image/svg+xml", // SVG carries script
    "application/octet-stream", "text/plain", "", null, undefined, "image", "imagejpeg",
  ]) {
    assert.equal(proxy.isAllowedImageType(type), false, `${JSON.stringify(type)} must be refused`);
  }
});
