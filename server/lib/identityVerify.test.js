"use strict";

// Tests for lib/identityVerify.js (S07-C1 part 2 of 2).
//
// Every signature used here is produced by @signalapp/libsignal-client itself
// (PrivateKey.sign) — the same library and version the verifier and the Android
// client use. No signature in this file is hand-constructed, and no test asserts
// a "expected" hex blob typed from memory. This is deliberate: earlier work in
// this remediation program fabricated test evidence, so these tests are written
// so that they cannot pass unless real cryptographic verification is happening.
//
// Run: node --test lib/identityVerify.test.js

const test = require("node:test");
const assert = require("node:assert");
const crypto = require("node:crypto");
const { PrivateKey } = require("@signalapp/libsignal-client");

const {
  verifyMintTokenSignature,
  buildMintTokenChallenge,
  CHALLENGE_CONTEXT,
} = require("./identityVerify");
const { createChallengeStore } = require("./challengeStore");

const USER_ID = "ABCDE-FGHIJ-KLM";

function freshNonceHex() {
  return crypto.randomBytes(32).toString("hex");
}

/** Produces a genuine signature over the real challenge bytes. */
function signChallenge(priv, userId, nonceHex) {
  return priv.sign(buildMintTokenChallenge(userId, nonceHex)).toString("hex");
}

function newIdentity() {
  const priv = PrivateKey.generate();
  return { priv, pubHex: priv.getPublicKey().serialize().toString("hex") };
}

test("accepts a genuine signature over the issued nonce", () => {
  const { priv, pubHex } = newIdentity();
  const nonceHex = freshNonceHex();
  assert.strictEqual(
    verifyMintTokenSignature({
      userId: USER_ID,
      identityPubKeyHex: pubHex,
      nonceHex,
      signatureHex: signChallenge(priv, USER_ID, nonceHex),
    }),
    true
  );
});

test("client public key is the 33-byte 0x05-prefixed form (matches Android serialize())", () => {
  const { pubHex } = newIdentity();
  assert.strictEqual(pubHex.length, 66);
  assert.strictEqual(pubHex.slice(0, 2), "05");
});

test("rejects a signature made by a DIFFERENT identity key (the core attack)", () => {
  // This is the actual S07-C1 attack: the attacker knows the victim's PUBLIC key
  // (it is world-readable for X3DH) but not the private key. They must not be
  // able to mint a token.
  const victim = newIdentity();
  const attacker = newIdentity();
  const nonceHex = freshNonceHex();
  assert.strictEqual(
    verifyMintTokenSignature({
      userId: USER_ID,
      identityPubKeyHex: victim.pubHex, // victim's public key, as an attacker would send
      nonceHex,
      signatureHex: signChallenge(attacker.priv, USER_ID, nonceHex), // attacker's key
    }),
    false
  );
});

test("rejects a signature over a different nonce (replay of an old challenge)", () => {
  const { priv, pubHex } = newIdentity();
  const signedNonce = freshNonceHex();
  const presentedNonce = freshNonceHex();
  assert.notStrictEqual(signedNonce, presentedNonce);
  assert.strictEqual(
    verifyMintTokenSignature({
      userId: USER_ID,
      identityPubKeyHex: pubHex,
      nonceHex: presentedNonce,
      signatureHex: signChallenge(priv, USER_ID, signedNonce),
    }),
    false
  );
});

test("rejects a signature bound to a different userId (domain separation works)", () => {
  const { priv, pubHex } = newIdentity();
  const nonceHex = freshNonceHex();
  assert.strictEqual(
    verifyMintTokenSignature({
      userId: "VICTIM-USERID-XYZ",
      identityPubKeyHex: pubHex,
      nonceHex,
      signatureHex: signChallenge(priv, "OTHER-USERID-ABC", nonceHex),
    }),
    false
  );
});

test("rejects a signature over the bare nonce (context prefix is required)", () => {
  // Guards the domain-separation decision: a signature over just the nonce bytes
  // must not validate, or the construction has silently lost its prefix.
  const { priv, pubHex } = newIdentity();
  const nonceHex = freshNonceHex();
  const bareSig = priv.sign(Buffer.from(nonceHex, "hex")).toString("hex");
  assert.strictEqual(
    verifyMintTokenSignature({
      userId: USER_ID,
      identityPubKeyHex: pubHex,
      nonceHex,
      signatureHex: bareSig,
    }),
    false
  );
});

test("rejects a single-bit-flipped signature", () => {
  const { priv, pubHex } = newIdentity();
  const nonceHex = freshNonceHex();
  const good = Buffer.from(signChallenge(priv, USER_ID, nonceHex), "hex");
  for (const bitIndex of [0, 7, 255, 511]) {
    const bad = Buffer.from(good);
    bad[bitIndex >> 3] ^= 1 << (bitIndex & 7);
    assert.strictEqual(
      verifyMintTokenSignature({
        userId: USER_ID,
        identityPubKeyHex: pubHex,
        nonceHex,
        signatureHex: bad.toString("hex"),
      }),
      false,
      `bit ${bitIndex} flip should not verify`
    );
  }
});

test("rejects malformed inputs without throwing", () => {
  const { priv, pubHex } = newIdentity();
  const nonceHex = freshNonceHex();
  const sigHex = signChallenge(priv, USER_ID, nonceHex);

  const cases = [
    ["missing everything", {}],
    ["null userId", { userId: null, identityPubKeyHex: pubHex, nonceHex, signatureHex: sigHex }],
    ["empty userId", { userId: "", identityPubKeyHex: pubHex, nonceHex, signatureHex: sigHex }],
    ["non-string userId", { userId: 42, identityPubKeyHex: pubHex, nonceHex, signatureHex: sigHex }],
    // 32-byte raw key (prefix stripped) — libsignal rejects this as "bad key type";
    // verify we return false rather than propagating a native throw.
    ["32-byte raw key", { userId: USER_ID, identityPubKeyHex: pubHex.slice(2), nonceHex, signatureHex: sigHex }],
    ["wrong key type byte", { userId: USER_ID, identityPubKeyHex: "01" + pubHex.slice(2), nonceHex, signatureHex: sigHex }],
    ["non-hex key", { userId: USER_ID, identityPubKeyHex: "z".repeat(66), nonceHex, signatureHex: sigHex }],
    ["short nonce", { userId: USER_ID, identityPubKeyHex: pubHex, nonceHex: "ab", signatureHex: sigHex }],
    ["non-hex nonce", { userId: USER_ID, identityPubKeyHex: pubHex, nonceHex: "q".repeat(64), signatureHex: sigHex }],
    ["short signature", { userId: USER_ID, identityPubKeyHex: pubHex, nonceHex, signatureHex: "00" }],
    ["empty signature", { userId: USER_ID, identityPubKeyHex: pubHex, nonceHex, signatureHex: "" }],
    ["all-zero signature", { userId: USER_ID, identityPubKeyHex: pubHex, nonceHex, signatureHex: "00".repeat(64) }],
  ];

  for (const [label, args] of cases) {
    assert.strictEqual(verifyMintTokenSignature(args), false, label);
  }
});

test("challenge bytes have the documented layout", () => {
  const nonceHex = freshNonceHex();
  const msg = buildMintTokenChallenge(USER_ID, nonceHex);
  const expectedLength =
    Buffer.byteLength(CHALLENGE_CONTEXT) + 1 + Buffer.byteLength(USER_ID) + 1 + 32;
  assert.strictEqual(msg.length, expectedLength);
  assert.strictEqual(msg.subarray(0, CHALLENGE_CONTEXT.length).toString("utf8"), CHALLENGE_CONTEXT);
  assert.strictEqual(msg[CHALLENGE_CONTEXT.length], 0x00);
  assert.strictEqual(msg.subarray(msg.length - 32).toString("hex"), nonceHex);
});

test("buildMintTokenChallenge is deterministic and rejects bad nonce lengths", () => {
  const nonceHex = freshNonceHex();
  assert.deepStrictEqual(
    buildMintTokenChallenge(USER_ID, nonceHex),
    buildMintTokenChallenge(USER_ID, nonceHex)
  );
  assert.throws(() => buildMintTokenChallenge(USER_ID, "abcd"), TypeError);
  assert.throws(() => buildMintTokenChallenge("", nonceHex), TypeError);
});

// ── Gate composition ─────────────────────────────────────────────────────────
//
// These mirror the exact sequence /mintToken performs (index.js: consume() then
// verifyMintTokenSignature()). They exercise the two modules composed the way the
// handler composes them. NOTE: this is NOT a test of the HTTP handler itself —
// that would need firebase-admin credentials this environment does not have. It
// tests the security logic the handler delegates to, nothing more.

/** Mirrors /mintToken's gate: consume the nonce, then verify the signature. */
function mintGate(store, { userId, identityPubKeyHex, nonce, signatureHex }) {
  if (!store.consume(userId, nonce)) return "reject:nonce";
  if (!verifyMintTokenSignature({ userId, identityPubKeyHex, nonceHex: nonce, signatureHex })) {
    return "reject:signature";
  }
  return "allow";
}

test("gate: legitimate owner passes exactly once, replay of same nonce fails", () => {
  const store = createChallengeStore();
  const { priv, pubHex } = newIdentity();
  const nonce = store.issue(USER_ID);
  const req = {
    userId: USER_ID,
    identityPubKeyHex: pubHex,
    nonce,
    signatureHex: signChallenge(priv, USER_ID, nonce),
  };

  assert.strictEqual(mintGate(store, req), "allow");
  // Replaying the identical request — a captured-on-the-wire signature — must
  // fail, because the nonce is single-use.
  assert.strictEqual(mintGate(store, req), "reject:nonce");
});

test("gate: S07-C1 attacker with only the victim's PUBLIC key is rejected", () => {
  // The full original attack, end to end: the attacker reads the victim's
  // world-readable identity public key, requests a challenge for the victim's
  // userId (which is also not secret), and tries to mint. Without the private
  // key they cannot produce a valid signature.
  const store = createChallengeStore();
  const victim = newIdentity();
  const attacker = newIdentity();

  const nonce = store.issue(USER_ID);
  assert.strictEqual(
    mintGate(store, {
      userId: USER_ID,
      identityPubKeyHex: victim.pubHex,
      nonce,
      signatureHex: signChallenge(attacker.priv, USER_ID, nonce),
    }),
    "reject:signature"
  );

  // And the attempt burned the nonce, so they cannot grind guesses against it.
  const nonce2 = store.issue(USER_ID);
  assert.notStrictEqual(nonce2, nonce);
  assert.strictEqual(
    mintGate(store, {
      userId: USER_ID,
      identityPubKeyHex: victim.pubHex,
      nonce, // stale
      signatureHex: signChallenge(attacker.priv, USER_ID, nonce),
    }),
    "reject:nonce"
  );
});

test("gate: a signature with no challenge ever issued is rejected", () => {
  const store = createChallengeStore();
  const { priv, pubHex } = newIdentity();
  const forgedNonce = freshNonceHex(); // never issued by the server
  assert.strictEqual(
    mintGate(store, {
      userId: USER_ID,
      identityPubKeyHex: pubHex,
      nonce: forgedNonce,
      signatureHex: signChallenge(priv, USER_ID, forgedNonce),
    }),
    "reject:nonce"
  );
});

test("gate: an expired challenge is rejected even with a valid signature", () => {
  let clock = 1_000_000;
  const store = createChallengeStore({ ttlMs: 1000, now: () => clock });
  const { priv, pubHex } = newIdentity();
  const nonce = store.issue(USER_ID);
  clock += 5000; // past TTL
  assert.strictEqual(
    mintGate(store, {
      userId: USER_ID,
      identityPubKeyHex: pubHex,
      nonce,
      signatureHex: signChallenge(priv, USER_ID, nonce),
    }),
    "reject:nonce"
  );
});

test("gate: a challenge issued for one account cannot mint another", () => {
  const store = createChallengeStore();
  const { priv, pubHex } = newIdentity();
  const nonce = store.issue("ACCOUNT-AAA");
  // Same key, same nonce value, but presented against a different userId.
  assert.strictEqual(
    mintGate(store, {
      userId: "ACCOUNT-BBB",
      identityPubKeyHex: pubHex,
      nonce,
      signatureHex: signChallenge(priv, "ACCOUNT-BBB", nonce),
    }),
    "reject:nonce"
  );
});

// Fixed cross-implementation vector. The private key is a hardcoded 32-byte
// scalar, so the public key and challenge bytes below are reproducible on any
// platform — this is the vector to feed to the Android side (Curve.calculate-
// Signature over the same challenge bytes) to prove the two implementations
// agree. The signature itself is NOT hardcoded: XEdDSA signing is randomised,
// so we assert that a freshly produced signature verifies, and we pin the
// deterministic parts (public key, challenge bytes).
test("fixed test vector: deterministic key + challenge bytes, live signature verifies", () => {
  const privBytes = Buffer.alloc(32);
  for (let i = 0; i < 32; i++) privBytes[i] = i + 1; // 0x01..0x20
  const priv = PrivateKey.deserialize(privBytes);
  const pubHex = priv.getPublicKey().serialize().toString("hex");
  const nonceHex = "00".repeat(31) + "2a"; // fixed 32-byte nonce

  const challenge = buildMintTokenChallenge(USER_ID, nonceHex);
  // Deterministic, portable expectation: context||0x00||userId||0x00||nonce
  assert.strictEqual(
    challenge.toString("hex"),
    Buffer.concat([
      Buffer.from(CHALLENGE_CONTEXT, "utf8"),
      Buffer.from([0x00]),
      Buffer.from(USER_ID, "utf8"),
      Buffer.from([0x00]),
      Buffer.from(nonceHex, "hex"),
    ]).toString("hex")
  );

  // Print the vector so a future session can paste it into an Android test
  // without re-deriving it. (Visible with `node --test` in verbose output.)
  console.log("[vector] identityPubKeyHex =", pubHex);
  console.log("[vector] nonceHex          =", nonceHex);
  console.log("[vector] challengeHex      =", challenge.toString("hex"));

  assert.strictEqual(
    verifyMintTokenSignature({
      userId: USER_ID,
      identityPubKeyHex: pubHex,
      nonceHex,
      signatureHex: priv.sign(challenge).toString("hex"),
    }),
    true
  );
});
