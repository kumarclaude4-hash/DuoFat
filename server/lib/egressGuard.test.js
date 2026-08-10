"use strict";

// Unit tests for lib/egressGuard.js (S04-H1, S04-H2).
//
// Every "bypass" case below is a form that the PREVIOUS predicate
// (`pure.js:isBlockedPreviewHost`) let through — they are regression tests
// against a real, verified gap, not hypotheticals. Where a test asserts an
// encoding reaches loopback, the expectation was cross-checked against Node's
// own URL/socket normalisation rather than written from intuition.

const test = require("node:test");
const assert = require("node:assert/strict");

const guard = require("./egressGuard");
const pure = require("./pure");

// ── IPv4 literal parsing, all inet_aton encodings ─────────────────────────────

test("parseIpv4Literal handles dotted, decimal, hex, octal and short forms", () => {
  const loopback = 0x7f000001;
  assert.equal(guard.parseIpv4Literal("127.0.0.1"), loopback);
  assert.equal(guard.parseIpv4Literal("2130706433"), loopback, "decimal");
  assert.equal(guard.parseIpv4Literal("0x7f000001"), loopback, "hex, single part");
  assert.equal(guard.parseIpv4Literal("0x7f.0x0.0x0.0x1"), loopback, "hex, per-part");
  assert.equal(guard.parseIpv4Literal("0177.0.0.1"), loopback, "octal");
  assert.equal(guard.parseIpv4Literal("127.1"), loopback, "short form");
  assert.equal(guard.parseIpv4Literal("127.0.1"), 0x7f000001, "three-part short form");
});

test("parseIpv4Literal rejects names and out-of-range values", () => {
  assert.equal(guard.parseIpv4Literal("example.com"), null);
  assert.equal(guard.parseIpv4Literal("127.0.0.256"), null);
  assert.equal(guard.parseIpv4Literal("1.2.3.4.5"), null);
  assert.equal(guard.parseIpv4Literal("127.0.0."), null);
  assert.equal(guard.parseIpv4Literal("0x7f.example"), null);
  assert.equal(guard.parseIpv4Literal(""), null);
});

// ── IPv6 literal parsing ──────────────────────────────────────────────────────

test("parseIpv6Literal expands :: and strips brackets and zone ids", () => {
  assert.deepEqual(guard.parseIpv6Literal("::1"), [0, 0, 0, 0, 0, 0, 0, 1]);
  assert.deepEqual(guard.parseIpv6Literal("[::1]"), [0, 0, 0, 0, 0, 0, 0, 1], "URL.hostname keeps brackets");
  assert.deepEqual(guard.parseIpv6Literal("fe80::1%eth0"), [0xfe80, 0, 0, 0, 0, 0, 0, 1], "zone id dropped");
  assert.deepEqual(guard.parseIpv6Literal("::"), [0, 0, 0, 0, 0, 0, 0, 0]);
  assert.deepEqual(
    guard.parseIpv6Literal("2001:db8:0:0:0:0:0:1"),
    [0x2001, 0x0db8, 0, 0, 0, 0, 0, 1],
    "fully expanded form"
  );
});

test("parseIpv6Literal embeds a trailing IPv4 quad in the last two groups", () => {
  assert.deepEqual(
    guard.parseIpv6Literal("::ffff:127.0.0.1"),
    [0, 0, 0, 0, 0, 0xffff, 0x7f00, 0x0001]
  );
});

test("parseIpv6Literal rejects malformed input", () => {
  assert.equal(guard.parseIpv6Literal("example.com"), null);
  assert.equal(guard.parseIpv6Literal("1::2::3"), null, "two :: runs");
  assert.equal(guard.parseIpv6Literal("gggg::1"), null, "non-hex group");
  assert.equal(guard.parseIpv6Literal("::ffff:127.0.0"), null, "short embedded quad");
  assert.equal(guard.parseIpv6Literal("127.0.0.1"), null, "IPv4 is not IPv6");
});

// ── Address policy ────────────────────────────────────────────────────────────

test("isBlockedIpAddress blocks every non-public IPv4 range", () => {
  for (const address of [
    "0.0.0.0", "0.1.2.3",
    "10.0.0.1", "10.255.255.255",
    "100.64.0.1",
    "127.0.0.1", "127.1.2.3",
    "169.254.169.254", "169.254.169.253", "169.254.0.1",
    "172.16.0.1", "172.31.255.255",
    "192.0.0.1",
    "192.168.1.1",
    "198.18.0.1",
    "224.0.0.1",
    "255.255.255.255",
  ]) {
    const verdict = guard.isBlockedIpAddress(address);
    assert.equal(verdict.blocked, true, `${address} must be blocked`);
    assert.equal(verdict.isIpLiteral, true);
    assert.ok(verdict.reason.length > 0, `${address} must carry a reason`);
  }
});

test("isBlockedIpAddress allows public IPv4", () => {
  for (const address of ["8.8.8.8", "1.1.1.1", "93.184.216.34", "172.32.0.1", "100.128.0.1"]) {
    assert.equal(guard.isBlockedIpAddress(address).blocked, false, `${address} must be allowed`);
  }
});

test("isBlockedIpAddress blocks non-public IPv6 including IPv4-mapped loopback", () => {
  for (const address of [
    "::1", "[::1]", "::",
    "fd00::1", "fc00::1",
    "fe80::1", "[fe80::abcd]",
    "ff02::1",
    "64:ff9b::7f00:1",
    "::ffff:127.0.0.1",
    "::ffff:169.254.169.254",
    "::ffff:10.0.0.1",
  ]) {
    const verdict = guard.isBlockedIpAddress(address);
    assert.equal(verdict.blocked, true, `${address} must be blocked`);
  }
});

test("isBlockedIpAddress allows public IPv6", () => {
  assert.equal(guard.isBlockedIpAddress("2606:4700:4700::1111").blocked, false);
  assert.equal(guard.isBlockedIpAddress("[2606:4700::1]").blocked, false);
  assert.equal(guard.isBlockedIpAddress("::ffff:8.8.8.8").blocked, false, "IPv4-mapped public is fine");
});

test("isBlockedIpAddress reports a plain name as not-a-literal, not as safe", () => {
  const verdict = guard.isBlockedIpAddress("example.com");
  assert.equal(verdict.isIpLiteral, false);
  assert.equal(verdict.blocked, false, "a name is undecided until resolved, never pre-blocked");
});

test("isBlockedHostName blocks reserved names and TLDs", () => {
  for (const host of [
    "localhost", "LOCALHOST", "localhost.",
    "metadata", "metadata.google.internal", "metadata.goog", "instance-data",
    "db.internal", "printer.local", "foo.localhost", "router.home.arpa",
  ]) {
    assert.equal(guard.isBlockedHostName(host).blocked, true, `${host} must be blocked`);
  }
  assert.equal(guard.isBlockedHostName("example.com").blocked, false);
  assert.equal(guard.isBlockedHostName("internal.example.com").blocked, false, "suffix match only");
});

// ── Regression: forms the OLD predicate let through ───────────────────────────

test("REGRESSION: forms pure.isBlockedPreviewHost missed are now blocked", () => {
  // Each of these returns false from the old predicate (verified by the first
  // assertion) while being a genuine internal target (second assertion).
  const bypasses = [
    "0.0.0.0",
    "2130706433",
    "0x7f000001",
    "0177.0.0.1",
    // NOTE: "127.1" is deliberately NOT in this list. The old predicate's
    // `127\.` prefix does match it, so it was never a bypass — asserting
    // otherwise would have been a fabricated gap. Verified by running the old
    // predicate against it directly.
    "[fd00::1]",
    "[fe80::1]",
    "[::ffff:127.0.0.1]",
    "169.254.169.253",
    "100.64.0.1",
    "[::]",
  ];
  for (const host of bypasses) {
    assert.equal(
      pure.isBlockedPreviewHost(host),
      false,
      `precondition: old predicate should have missed ${host} (if this fails the gap was closed elsewhere)`
    );
    assert.equal(
      guard.isBlockedIpAddress(host).blocked,
      true,
      `${host} must now be blocked by egressGuard`
    );
  }
});

test("the old predicate's own catches still hold in the new guard", () => {
  // Defence in depth: callers run both, so make sure the new guard is a
  // superset for the cases the old one did handle.
  for (const host of ["127.0.0.1", "10.0.0.1", "192.168.0.1", "172.16.0.1", "169.254.169.254"]) {
    assert.equal(pure.isBlockedPreviewHost(host), true, `old predicate caught ${host}`);
    assert.equal(guard.isBlockedIpAddress(host).blocked, true, `new guard also catches ${host}`);
  }
  for (const host of ["localhost", "metadata.google.internal", "x.internal", "y.local"]) {
    assert.equal(pure.isBlockedPreviewHost(host), true);
    assert.equal(guard.isBlockedHostName(host).blocked, true);
  }
});

// ── URL-level verdict ─────────────────────────────────────────────────────────

test("evaluatePreviewTarget accepts a normal public https URL", () => {
  const verdict = guard.evaluatePreviewTarget("https://example.com/article?id=1");
  assert.equal(verdict.ok, true);
  assert.equal(verdict.hostname, "example.com");
  assert.equal(verdict.isIpLiteral, false);
});

test("evaluatePreviewTarget rejects non-http schemes, credentials and blocked hosts", () => {
  const cases = [
    ["file:///etc/passwd", /scheme/],
    ["gopher://example.com/", /scheme/],
    ["javascript:alert(1)", /scheme/],
    ["data:text/html,<h1>x", /scheme/],
    ["http://user:pw@example.com/", /credentials/],
    ["http://127.0.0.1/", /127\.0\.0\.0\/8/],
    ["http://[::1]/", /loopback/],
    ["http://2130706433/", /127\.0\.0\.0\/8/],
    ["http://169.254.169.254/latest/meta-data/", /169\.254\.0\.0\/16/],
    ["http://metadata.google.internal/", /reserved hostname/],
    ["not a url", /malformed/],
  ];
  for (const [input, expected] of cases) {
    const verdict = guard.evaluatePreviewTarget(input);
    assert.equal(verdict.ok, false, `${input} must be rejected`);
    assert.match(verdict.reason, expected, `${input} reason`);
  }
});

test("evaluatePreviewTarget marks a public IP literal as a literal", () => {
  const verdict = guard.evaluatePreviewTarget("http://8.8.8.8/");
  assert.equal(verdict.ok, true);
  assert.equal(verdict.isIpLiteral, true, "literals skip DNS in resolveAndCheckHost");
});

// ── Content-Length / body cap (S04-H2) ────────────────────────────────────────

test("contentLengthExceeds compares a declared length against the cap", () => {
  assert.equal(guard.contentLengthExceeds("100", 1000), false);
  assert.equal(guard.contentLengthExceeds("1000", 1000), false, "equal is not over");
  assert.equal(guard.contentLengthExceeds("1001", 1000), true);
  // Absent or junk headers must NOT reject — the streaming counter covers them.
  assert.equal(guard.contentLengthExceeds(null, 1000), false);
  assert.equal(guard.contentLengthExceeds("", 1000), false);
  assert.equal(guard.contentLengthExceeds("not-a-number", 1000), false);
  assert.equal(guard.contentLengthExceeds("-5", 1000), false);
});

// Minimal Response stand-in so the cap can be tested without network I/O.
function responseFrom(chunks, headers = {}) {
  const map = new Map(Object.entries(headers).map(([k, v]) => [k.toLowerCase(), v]));
  let cancelled = false;
  let index = 0;
  return {
    get cancelled() { return cancelled; },
    headers: { get: (name) => (map.has(name.toLowerCase()) ? map.get(name.toLowerCase()) : null) },
    body: {
      getReader() {
        return {
          read: async () =>
            index < chunks.length ? { done: false, value: chunks[index++] } : { done: true, value: undefined },
          cancel: async () => { cancelled = true; },
        };
      },
    },
  };
}

test("readCappedBody rejects an oversized declared Content-Length without reading", async () => {
  const response = responseFrom([Buffer.alloc(10)], { "content-length": "999999999" });
  await assert.rejects(() => guard.readCappedBody(response, 1024), /too large/);
  assert.equal(response.cancelled, false, "no body read was started");
});

test("readCappedBody stops at the cap when the body lies about its size", async () => {
  // 10 chunks x 1 KB, but the cap is 4 KB and no Content-Length is declared —
  // this is the exact `await r.text()` OOM vector from S04-H2.
  const chunks = Array.from({ length: 10 }, () => Buffer.alloc(1024, 0x61));
  const response = responseFrom(chunks);
  const result = await guard.readCappedBody(response, 4096);
  assert.equal(result.truncated, true);
  assert.equal(result.buffer.length, 4096, "never more than the cap is retained");
  assert.equal(response.cancelled, true, "the upstream stream is cancelled, not drained");
});

test("readCappedBody returns a small body intact and untruncated", async () => {
  const response = responseFrom([Buffer.from("<html><head></head></html>")]);
  const result = await guard.readCappedBody(response, 4096);
  assert.equal(result.truncated, false);
  assert.equal(result.buffer.toString("utf8"), "<html><head></head></html>");
});

test("readCappedBody tolerates a bodyless response", async () => {
  const result = await guard.readCappedBody(
    { headers: { get: () => null }, body: null },
    4096
  );
  assert.equal(result.bytes, 0);
  assert.equal(result.buffer.length, 0);
});

// ── DNS-aware check (S04-H1's central gap) ────────────────────────────────────

test("resolveAndCheckHost blocks a public NAME that resolves to a private address", async () => {
  // The defining S04-H1 bypass: attacker controls DNS for a public-looking host.
  const fakeLookup = async () => [{ address: "10.0.0.5", family: 4 }];
  const verdict = await guard.resolveAndCheckHost("evil.example.com", fakeLookup);
  assert.equal(verdict.ok, false);
  assert.match(verdict.reason, /resolves to blocked address/);
  assert.match(verdict.reason, /10\.0\.0\.0\/8/);
});

test("resolveAndCheckHost blocks if ANY record is private, not just the first", async () => {
  const fakeLookup = async () => [
    { address: "93.184.216.34", family: 4 },
    { address: "127.0.0.1", family: 4 },
  ];
  const verdict = await guard.resolveAndCheckHost("split.example.com", fakeLookup);
  assert.equal(verdict.ok, false, "a mixed record set must not be a coin flip");
});

test("resolveAndCheckHost blocks a name resolving to an IPv6 unique-local address", async () => {
  const fakeLookup = async () => [{ address: "fd00::1", family: 6 }];
  const verdict = await guard.resolveAndCheckHost("v6.example.com", fakeLookup);
  assert.equal(verdict.ok, false);
  assert.match(verdict.reason, /unique-local/);
});

test("resolveAndCheckHost allows a name that resolves entirely to public space", async () => {
  const fakeLookup = async () => [
    { address: "93.184.216.34", family: 4 },
    { address: "2606:2800:220:1::1", family: 6 },
  ];
  const verdict = await guard.resolveAndCheckHost("example.com", fakeLookup);
  assert.equal(verdict.ok, true);
  assert.deepEqual(verdict.addresses, ["93.184.216.34", "2606:2800:220:1::1"]);
});

test("resolveAndCheckHost fails closed on DNS errors and empty answers", async () => {
  const failing = async () => { const e = new Error("nope"); e.code = "ENOTFOUND"; throw e; };
  const failed = await guard.resolveAndCheckHost("nx.example.com", failing);
  assert.equal(failed.ok, false);
  assert.match(failed.reason, /ENOTFOUND/);

  const empty = await guard.resolveAndCheckHost("empty.example.com", async () => []);
  assert.equal(empty.ok, false);
  assert.match(empty.reason, /no addresses/);
});

test("resolveAndCheckHost short-circuits IP literals without calling DNS", async () => {
  let called = false;
  const spy = async () => { called = true; return []; };

  const blocked = await guard.resolveAndCheckHost("127.0.0.1", spy);
  assert.equal(blocked.ok, false);
  assert.equal(called, false, "a blocked literal needs no DNS");

  const allowed = await guard.resolveAndCheckHost("8.8.8.8", spy);
  assert.equal(allowed.ok, true);
  assert.deepEqual(allowed.addresses, ["8.8.8.8"]);
  assert.equal(called, false, "an allowed literal needs no DNS either");

  const bracketed = await guard.resolveAndCheckHost("[2606:4700::1]", spy);
  assert.equal(bracketed.ok, true);
  assert.deepEqual(bracketed.addresses, ["2606:4700::1"], "brackets stripped for the caller");
});
