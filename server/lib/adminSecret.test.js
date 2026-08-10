"use strict";

const { test } = require("node:test");
const assert = require("node:assert");
const crypto = require("node:crypto");

const {
  evaluateSecretStrength,
  shannonBits,
  MIN_SECRET_BYTES,
} = require("./adminSecret");

// The value the finding is actually about: the old code accepted ANY non-empty
// string, so each of these booted a server whose admin API was guarded by it.
test("rejects the weak tokens the unvalidated env read used to accept", () => {
  const weak = [
    "admin",
    "admin123",
    "changeme",
    "password123",
    "secret",
    "duoshield",
    "hunter2",
    "a",
    "1234567890",
    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", // long but ~zero entropy
    "abababababababababababababababab", // long, two distinct chars
  ];
  for (const value of weak) {
    const verdict = evaluateSecretStrength("ADMIN_TOKEN", value);
    assert.equal(verdict.ok, false, `expected "${value}" to be rejected`);
    assert.match(verdict.reason, /ADMIN_TOKEN/);
    assert.ok(verdict.remedy, "a rejection must tell the operator how to fix it");
  }
});

test("accepts what the documented remedy actually produces", () => {
  // `openssl rand -hex 32` — the exact command in the remedy string. If this
  // failed, the module would be telling operators to do something it rejects.
  for (let i = 0; i < 50; i++) {
    const generated = crypto.randomBytes(32).toString("hex");
    const verdict = evaluateSecretStrength("ADMIN_TOKEN", generated);
    assert.equal(verdict.ok, true, `rejected a valid hex-32 secret: ${generated}`);
    assert.ok(verdict.bits >= MIN_SECRET_BYTES * 8);
  }
});

test("accepts base64 and hex-16 secrets, rejects hex-8", () => {
  assert.equal(evaluateSecretStrength("S", crypto.randomBytes(32).toString("base64")).ok, true);
  assert.equal(evaluateSecretStrength("S", crypto.randomBytes(16).toString("hex")).ok, true);
  // 8 random bytes = 64 bits, genuinely below the 128-bit floor.
  assert.equal(evaluateSecretStrength("S", crypto.randomBytes(8).toString("hex")).ok, false);
});

test("unset and whitespace-padded values are distinct, actionable errors", () => {
  const unset = evaluateSecretStrength("ADMIN_TOKEN", "");
  assert.equal(unset.ok, false);
  assert.match(unset.reason, /not set/);

  assert.equal(evaluateSecretStrength("ADMIN_TOKEN", undefined).ok, false);
  assert.equal(evaluateSecretStrength("ADMIN_TOKEN", null).ok, false);

  // A strong secret with a stray newline must be reported as WHITESPACE, not as
  // weak entropy — the operator's fix is completely different.
  const padded = evaluateSecretStrength("ADMIN_TOKEN", crypto.randomBytes(32).toString("hex") + "\n");
  assert.equal(padded.ok, false);
  assert.match(padded.reason, /whitespace/i);
});

test("placeholder detection is case-insensitive and substring-based", () => {
  // Long enough to clear the entropy floor on length alone, so these can only
  // be caught by the placeholder list.
  const cases = ["ChangeMe-9f3a2b7c1d4e8f0a2b6c", "PLACEHOLDER-9f3a2b7c1d4e8f0a", "my-test-9f3a2b7c1d4e8f0a2b6c"];
  for (const value of cases) {
    const verdict = evaluateSecretStrength("ADMIN_TOKEN", value);
    assert.equal(verdict.ok, false, `expected "${value}" rejected as placeholder`);
    assert.match(verdict.reason, /placeholder/);
  }
});

test("the entropy estimate is the MINIMUM of both readings", () => {
  // A 40-char string over a 2-char alphabet: the alphabet reading is generous
  // (40 bits) and Shannon is ~40 too, but both are under 128 — the point is
  // that a length-only check would have passed 40 characters.
  const monotonous = "abababababababababababababababababababab";
  assert.equal(evaluateSecretStrength("S", monotonous).ok, false);
  assert.ok(shannonBits("aaaa") < 0.001, "identical chars carry no entropy");
  assert.ok(shannonBits("abcd") > 7, "four distinct chars carry ~8 bits");
});

test("reason names the variable so a multi-secret boot log is unambiguous", () => {
  const a = evaluateSecretStrength("ADMIN_TOKEN", "weak");
  const b = evaluateSecretStrength("LINK_PREVIEW_PROXY_SECRET", "weak");
  assert.match(a.reason, /ADMIN_TOKEN/);
  assert.match(b.reason, /LINK_PREVIEW_PROXY_SECRET/);
  assert.ok(!b.reason.includes("ADMIN_TOKEN"));
});
