"use strict";

// Unit tests for S02-H1 (migrateUid field allow-list) and S02-L2 (createChat
// display-name bound/sanitize). Run with: npm test  (inside server/).

const test = require("node:test");
const assert = require("node:assert/strict");
const {
  sanitizeMigratedUserFields,
  isValidDisplayName,
  MAX_DISPLAY_NAME_LEN,
} = require("./profileSanitize");

// ── S02-H1: sanitizeMigratedUserFields ───────────────────────────────────────

test("S02-H1: passes through only the allow-listed fields", () => {
  const out = sanitizeMigratedUserFields({
    displayName: "Alice",
    fcmToken: "tok-123",
    platform: "android",
    photoUrl: "https://example.com/a.png",
  });
  assert.deepEqual(out, {
    displayName: "Alice",
    fcmToken: "tok-123",
    platform: "android",
    photoUrl: "https://example.com/a.png",
  });
});

test("S02-H1: drops fields not in the allow-list (field injection)", () => {
  const out = sanitizeMigratedUserFields({
    displayName: "Alice",
    role: "admin",
    accountLock: { locked: false },
    identityPubKeyHash: "deadbeef",
    isSystemAccount: true,
  });
  assert.deepEqual(out, { displayName: "Alice" });
});

test("S02-H1: drops allow-listed fields with the wrong type", () => {
  const out = sanitizeMigratedUserFields({
    displayName: { toString: () => "Alice" }, // object, not a string
    fcmToken: 12345,
    platform: null,
    photoUrl: ["https://example.com/a.png"],
  });
  assert.deepEqual(out, {});
});

test("S02-H1: drops oversized allow-listed string fields", () => {
  const out = sanitizeMigratedUserFields({
    displayName: "x".repeat(201),
    platform: "x".repeat(33),
  });
  assert.deepEqual(out, {});
});

test("S02-H1: tolerates a missing/non-object document", () => {
  assert.deepEqual(sanitizeMigratedUserFields(undefined), {});
  assert.deepEqual(sanitizeMigratedUserFields(null), {});
  assert.deepEqual(sanitizeMigratedUserFields("not-an-object"), {});
});

// ── S02-L2: isValidDisplayName ───────────────────────────────────────────────

test("S02-L2: accepts a normal display name", () => {
  assert.equal(isValidDisplayName("Bob"), true);
});

test("S02-L2: rejects non-string values (content-injection surface)", () => {
  assert.equal(isValidDisplayName({ evil: true }), false);
  assert.equal(isValidDisplayName(["array"]), false);
  assert.equal(isValidDisplayName(12345), false);
  assert.equal(isValidDisplayName(null), false);
  assert.equal(isValidDisplayName(undefined), false);
});

test("S02-L2: rejects empty string", () => {
  assert.equal(isValidDisplayName(""), false);
});

test("S02-L2: accepts exactly the max length, rejects one over", () => {
  assert.equal(isValidDisplayName("x".repeat(MAX_DISPLAY_NAME_LEN)), true);
  assert.equal(isValidDisplayName("x".repeat(MAX_DISPLAY_NAME_LEN + 1)), false);
});
