"use strict";

// ── Identity-key proof-of-possession (S07-C1 remediation, part 2 of 2) ────────
//
// Part 1 (lib/challengeStore.js) issues a single-use nonce. This file is the
// consumer that finally gives that nonce a security effect: it verifies that the
// caller holds the identity PRIVATE key corresponding to the identity public key
// on file, by checking an XEdDSA signature over a server-issued nonce.
//
// Why this file exists at all — the bug it closes:
//   /mintToken used to "authenticate" by comparing sha256(identityPubKeyHex) to a
//   stored hash. The public key it hashes is readable by any authenticated user
//   (firestore.rules — public_keys/* is world-readable by design, X3DH needs it),
//   so that check proved nothing: anyone who could read a victim's public key
//   could mint a Firebase token for the victim's uid. See ../index.js's /mintToken
//   comment and ../../security-remediation/sessions/SESSION-01.md.
//
// Implementation note — deliberately NOT hand-rolled:
//   Verification is delegated entirely to @signalapp/libsignal-client, Signal's
//   own npm package with native bindings, pinned to 0.54.1 — the SAME version the
//   Android client uses (app/build.gradle: org.signal:libsignal-android:0.54.1 /
//   libsignal-client:0.54.1). No XEdDSA/Curve25519 math is reimplemented here. A
//   prior session in this program fabricated a hand-rolled `xed25519.js`; the
//   correct move is to use the vetted library, and that is what this does.
//
// Wire encoding, verified empirically against the library (not assumed):
//   • identityPubKeyHex — 66 hex chars = 33 bytes, leading 0x05 "DJB type" byte.
//     This is exactly what the Android client already sends: both call sites use
//     `identityKeyPair.getPublicKey().serialize()` (DisplayNameActivity.java,
//     RestoreFromSeedActivity.java:197), and Java's ECPublicKey.serialize()
//     emits the type-prefixed form. PublicKey.deserialize() here requires that
//     same 33-byte form — a bare 32-byte key throws "bad key type".
//   • signatureHex — 128 hex chars = 64 bytes (XEdDSA / Curve25519 signature),
//     as produced by Curve.calculateSignature() on Android.
//   • nonceHex — 64 hex chars = 32 bytes, as issued by challengeStore.
//
// Domain separation:
//   The signed message is NOT the bare nonce. The same identity key also signs
//   signed-prekeys elsewhere in the protocol (SignalKeyManager.java uses
//   Curve.calculateSignature on prekey bytes), so a bare-nonce signature could in
//   principle be confused with a signature made for another purpose. We bind the
//   signature to both this endpoint and the specific account:
//
//       "DuoShield-mintToken-v1" || 0x00 || utf8(userId) || 0x00 || nonceBytes
//
//   The Android client MUST build the identical byte string — see
//   buildMintTokenChallenge() below and its Java twin in AuthTokenHelper.java.
//   Any change to this construction is a breaking protocol change and needs the
//   version tag bumped on both sides together.

const { PublicKey } = require("@signalapp/libsignal-client");

const CHALLENGE_CONTEXT = "DuoShield-mintToken-v1";

const IDENTITY_KEY_BYTES = 33; // 0x05 prefix + 32-byte Curve25519 public key
const IDENTITY_KEY_TYPE_DJB = 0x05;
const SIGNATURE_BYTES = 64;
const NONCE_BYTES = 32;

/** Strict lowercase-or-uppercase hex of an exact byte length. */
function isHexOfBytes(value, byteLength) {
  return (
    typeof value === "string" &&
    value.length === byteLength * 2 &&
    /^[0-9a-fA-F]+$/.test(value)
  );
}

/**
 * Builds the exact byte sequence that must be signed. Exported so tests and the
 * Android client can be checked against one single definition instead of two
 * copies of the same string concatenation drifting apart.
 */
function buildMintTokenChallenge(userId, nonceHex) {
  if (typeof userId !== "string" || userId.length === 0) {
    throw new TypeError("userId must be a non-empty string");
  }
  if (!isHexOfBytes(nonceHex, NONCE_BYTES)) {
    throw new TypeError(`nonceHex must be ${NONCE_BYTES * 2} hex chars`);
  }
  return Buffer.concat([
    Buffer.from(CHALLENGE_CONTEXT, "utf8"),
    Buffer.from([0x00]),
    Buffer.from(userId, "utf8"),
    Buffer.from([0x00]),
    Buffer.from(nonceHex, "hex"),
  ]);
}

/**
 * Verifies proof of possession of the identity private key.
 *
 * Returns a plain boolean — never throws for malformed input, so a caller can
 * treat "unverifiable" and "invalid" identically without a try/catch that might
 * accidentally swallow a real fault into a success. Every rejection path returns
 * false; there is no path that returns true without libsignal's own
 * PublicKey.verify() having returned true.
 *
 * @param {object}  args
 * @param {string}  args.userId             account id the token is being minted for
 * @param {string}  args.identityPubKeyHex  66 hex chars, 0x05-prefixed (33 bytes)
 * @param {string}  args.nonceHex           64 hex chars (32 bytes) from /mintChallenge
 * @param {string}  args.signatureHex       128 hex chars (64 bytes)
 * @returns {boolean} true only if the signature is valid for this exact challenge
 */
function verifyMintTokenSignature({ userId, identityPubKeyHex, nonceHex, signatureHex }) {
  // ── Shape checks first (fail closed on anything unexpected) ────────────────
  if (typeof userId !== "string" || userId.length === 0) return false;
  if (!isHexOfBytes(identityPubKeyHex, IDENTITY_KEY_BYTES)) return false;
  if (!isHexOfBytes(nonceHex, NONCE_BYTES)) return false;
  if (!isHexOfBytes(signatureHex, SIGNATURE_BYTES)) return false;

  const keyBytes = Buffer.from(identityPubKeyHex, "hex");
  // Reject anything that is not a DJB/Curve25519 key before handing it to the
  // native layer. libsignal would reject it too ("bad key type"), but failing
  // here keeps the error path a boolean instead of a thrown native exception.
  if (keyBytes[0] !== IDENTITY_KEY_TYPE_DJB) return false;

  try {
    const publicKey = PublicKey.deserialize(keyBytes);
    const message = buildMintTokenChallenge(userId, nonceHex);
    const signature = Buffer.from(signatureHex, "hex");
    // The one and only source of a `true` result.
    return publicKey.verify(message, signature) === true;
  } catch (e) {
    // Malformed key material, native deserialisation failure, etc. Never treat
    // an exception as a pass.
    return false;
  }
}

module.exports = {
  verifyMintTokenSignature,
  buildMintTokenChallenge,
  CHALLENGE_CONTEXT,
  IDENTITY_KEY_BYTES,
  SIGNATURE_BYTES,
  NONCE_BYTES,
};
