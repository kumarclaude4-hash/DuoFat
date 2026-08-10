"use strict";

// Unit tests for the mint-challenge nonce store (S07-C1 remediation, part 1 of 2).
// Run with: npm test   (inside server/) — uses the Node built-in test runner.

const test = require("node:test");
const assert = require("node:assert/strict");
const { createChallengeStore, NONCE_BYTES } = require("./challengeStore");

test("issue returns a fresh hex nonce of the expected length", () => {
  const store = createChallengeStore();
  const nonce = store.issue("user-1");
  assert.equal(typeof nonce, "string");
  assert.equal(nonce.length, NONCE_BYTES * 2);
  assert.match(nonce, /^[0-9a-f]+$/);
});

test("consume succeeds exactly once for the correct nonce, then fails", () => {
  const store = createChallengeStore();
  const nonce = store.issue("user-1");
  assert.equal(store.consume("user-1", nonce), true);
  // Single-use: the same nonce must not work a second time.
  assert.equal(store.consume("user-1", nonce), false);
});

test("consume rejects a wrong nonce and does not burn the real one", () => {
  const store = createChallengeStore();
  const nonce = store.issue("user-1");
  assert.equal(store.consume("user-1", "00".repeat(NONCE_BYTES)), false);
  // The real nonce must still be valid after a failed guess.
  assert.equal(store.consume("user-1", nonce), true);
});

test("consume rejects an unknown userId", () => {
  const store = createChallengeStore();
  store.issue("user-1");
  assert.equal(store.consume("user-2", "00".repeat(NONCE_BYTES)), false);
});

test("consume rejects malformed input without throwing", () => {
  const store = createChallengeStore();
  const nonce = store.issue("user-1");
  assert.equal(store.consume("user-1", undefined), false);
  assert.equal(store.consume("user-1", 12345), false);
  assert.equal(store.consume("user-1", ""), false);
  assert.equal(store.consume("user-1", nonce.slice(0, -2)), false); // truncated
  assert.equal(store.consume("user-1", nonce + "00"), false); // extended
  // Real nonce still valid after all of the above.
  assert.equal(store.consume("user-1", nonce), true);
});

test("issuing a new nonce invalidates the previous outstanding one", () => {
  const store = createChallengeStore();
  const first = store.issue("user-1");
  const second = store.issue("user-1");
  assert.notEqual(first, second);
  assert.equal(store.consume("user-1", first), false);
  assert.equal(store.consume("user-1", second), true);
});

test("expired nonce is rejected and cleaned up", () => {
  let currentTime = 1_000_000;
  const store = createChallengeStore({ ttlMs: 1000, now: () => currentTime });
  const nonce = store.issue("user-1");
  currentTime += 1001; // past TTL
  assert.equal(store.consume("user-1", nonce), false);
  assert.equal(store._size(), 0); // cleaned up on the failed, expired attempt
});

test("nonce just inside the TTL window is still valid", () => {
  let currentTime = 1_000_000;
  const store = createChallengeStore({ ttlMs: 1000, now: () => currentTime });
  const nonce = store.issue("user-1");
  currentTime += 999; // just before TTL
  assert.equal(store.consume("user-1", nonce), true);
});

test("two different userIds get independent nonces", () => {
  const store = createChallengeStore();
  const nonceA = store.issue("user-a");
  const nonceB = store.issue("user-b");
  assert.notEqual(nonceA, nonceB);
  assert.equal(store.consume("user-a", nonceB), false); // cross-user swap fails
  assert.equal(store.consume("user-b", nonceA), false);
  assert.equal(store.consume("user-a", nonceA), true);
  assert.equal(store.consume("user-b", nonceB), true);
});
