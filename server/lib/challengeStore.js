"use strict";

// ── Mint-challenge nonce store (S07-C1 remediation, part 1 of 2) ──────────────
//
// This is the foundation for replacing /mintToken's broken "proof of ownership"
// (sha256 of a PUBLIC key — see index.js's /mintToken comment and
// ../REMEDIATION_PROGRESS.md) with real proof-of-possession: the client signs a
// server-issued, single-use nonce with the identity PRIVATE key, and the server
// verifies that signature against the public key on file.
//
// This file only issues and consumes nonces. It deliberately does NOT verify any
// signature — see ../SESSION_PROTOCOL.md's "Next session" prompt at the bottom of
// this file's directory-level doc for why that half is intentionally left for a
// dedicated session (native XEdDSA verification is easy to get subtly wrong and
// deserves an unhurried pass with its own budget, not a rushed addition here).
// /mintToken is UNCHANGED by this file and is still exploitable exactly as
// documented until the second half lands.
//
// Extracted as a pure-ish module (state is encapsulated, not global) so it can be
// unit-tested with the Node built-in test runner, same pattern as pure.js.

const crypto = require("crypto");

const NONCE_TTL_MS = 5 * 60 * 1000; // 5 minutes — generous enough for a mobile
                                     // client to sign and round-trip, short enough
                                     // to bound replay risk if a nonce ever leaked.
const NONCE_BYTES = 32;

function createChallengeStore({ ttlMs = NONCE_TTL_MS, now = Date.now } = {}) {
  // userId -> { nonceHex, expiresAt }
  const store = new Map();

  return {
    // Issues a fresh nonce for userId, replacing any prior unconsumed nonce for
    // the same userId (only the most recent challenge is ever valid — prevents a
    // client from accumulating multiple valid nonces to spend later).
    issue(userId) {
      const nonceHex = crypto.randomBytes(NONCE_BYTES).toString("hex");
      store.set(userId, { nonceHex, expiresAt: now() + ttlMs });
      return nonceHex;
    },

    // Consumes (single-use) the nonce for userId if `suppliedNonceHex` matches
    // the outstanding one and it has not expired. Returns true and deletes the
    // entry on success; returns false and leaves state unchanged on any failure
    // (wrong value, expired, or none outstanding) so a failed attempt can never
    // burn a legitimate nonce out from under a retry.
    consume(userId, suppliedNonceHex) {
      const entry = store.get(userId);
      if (!entry) return false;
      if (typeof suppliedNonceHex !== "string") return false;
      if (entry.expiresAt <= now()) {
        store.delete(userId); // expired — clean up, never valid again
        return false;
      }
      if (entry.nonceHex.length !== suppliedNonceHex.length) return false;
      const a = Buffer.from(entry.nonceHex, "hex");
      const b = Buffer.from(suppliedNonceHex, "hex");
      if (a.length !== b.length || a.length === 0) return false;
      if (!crypto.timingSafeEqual(a, b)) return false;
      store.delete(userId); // single-use: consumed regardless of what happens next
      return true;
    },

    // Test/ops helper only — not used by index.js.
    _size() {
      return store.size;
    },
  };
}

module.exports = { createChallengeStore, NONCE_TTL_MS, NONCE_BYTES };
