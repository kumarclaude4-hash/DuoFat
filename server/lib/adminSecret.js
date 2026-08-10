"use strict";

// ── Operator-secret strength gate (S05-H1) ───────────────────────────────────
//
// WHAT WAS ACTUALLY WRONG
//
// `ADMIN_TOKEN` was read straight out of the environment with no validation:
//
//     const ADMIN_TOKEN = process.env.ADMIN_TOKEN || "";
//
// so `ADMIN_TOKEN=admin` booted a server whose waitlist-approval and
// account-unfreeze endpoints were protected by a five-character password. The
// finding row for S05-H1 also claims there is "no working brute-force ceiling";
// that clause is STALE — `adminIpLocked()`/`recordAdminAuthFailure()` do enforce
// 10 failures per IP per 15 minutes. But a per-IP ceiling is not a substitute
// for entropy:
//
//   - The counter is keyed on IP, so N hosts get N × 10 attempts per window.
//     Against a 5-character lowercase token that is a completely tractable
//     search; against 32 hex characters it is not.
//   - The counter lives in a `Map` in process memory, so a restart (Render
//     redeploys, crashes, the `uncaughtException` guard) silently resets every
//     counter to zero.
//
// Entropy is the control that does not depend on server state surviving. Hence
// a floor enforced at STARTUP, where a weak value is a deployment error the
// operator can still fix, rather than at request time where it is invisible.
//
// This module is deliberately pure (no `process.env`, no I/O, no `process.exit`)
// so every branch below is unit-testable in `adminSecret.test.js`. The caller in
// index.js owns reading the environment and deciding to abort.

// 128 bits is the conventional floor for a secret that is not rate-limited in a
// way we can rely on. Expressed in characters per alphabet below rather than as
// a bit count, because operators paste strings, not entropy.
const MIN_SECRET_BYTES = 16;

// Rejected outright regardless of length: these are the values that actually
// show up in a hurried deploy, and a length check alone would pass several of
// them once padded (e.g. "changeme12345678" is 16 chars).
const FORBIDDEN_SUBSTRINGS = [
  "changeme",
  "change_me",
  "password",
  "secret",
  "admin",
  "token",
  "test",
  "example",
  "placeholder",
  "todo",
  "xxxx",
  "dummy",
  "default",
];

/**
 * Estimates the size of the alphabet a string appears to be drawn from. Used to
 * convert a character count into an entropy estimate: 16 characters of hex
 * (4 bits each) is 64 bits and NOT acceptable, while 16 characters of mixed
 * base64 (~6 bits each) is ~96 bits. Counting the observed classes avoids
 * calling a long-but-monotonous token strong.
 */
function estimateAlphabetSize(value) {
  let size = 0;
  if (/[a-z]/.test(value)) size += 26;
  if (/[A-Z]/.test(value)) size += 26;
  if (/[0-9]/.test(value)) size += 10;
  if (/[^a-zA-Z0-9]/.test(value)) size += 20; // conservative for punctuation
  return size;
}

/**
 * Shannon entropy over the observed character distribution, in bits per
 * character, times the length. This is what catches a long low-variety string:
 * "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" is 32 characters but ~0 bits, and a
 * length-only check would have waved it through.
 */
function shannonBits(value) {
  const counts = new Map();
  for (const ch of value) counts.set(ch, (counts.get(ch) || 0) + 1);
  let bitsPerChar = 0;
  for (const count of counts.values()) {
    const p = count / value.length;
    bitsPerChar -= p * Math.log2(p);
  }
  return bitsPerChar * value.length;
}

/**
 * Judges an operator secret. Returns
 *   { ok: true,  bits }                      — acceptable
 *   { ok: false, reason, bits, remedy }      — reject, with an actionable message
 *
 * `name` is only used to build the message so one implementation can serve
 * ADMIN_TOKEN, LINK_PREVIEW_PROXY_SECRET and MEDIA_TOKEN_SECRET alike.
 */
function evaluateSecretStrength(name, rawValue) {
  const value = typeof rawValue === "string" ? rawValue : "";
  const remedy = `Generate one with:  openssl rand -hex 32   then set ${name} to the output.`;

  if (value.length === 0) {
    return { ok: false, reason: `${name} is not set`, bits: 0, remedy };
  }

  // Trailing whitespace is a real and nasty failure mode: a secret pasted into
  // a dashboard field with a stray newline compares unequal to the same secret
  // pasted into curl, producing "the token is right but login fails". Treat it
  // as a configuration error instead of letting it become a support mystery.
  if (value !== value.trim()) {
    return {
      ok: false,
      reason: `${name} has leading or trailing whitespace, which will not compare equal to the value you think you set`,
      bits: 0,
      remedy: `Re-set ${name} with no surrounding spaces or newline.`,
    };
  }

  const lower = value.toLowerCase();
  const hit = FORBIDDEN_SUBSTRINGS.find((bad) => lower.includes(bad));
  if (hit) {
    return {
      ok: false,
      reason: `${name} contains the well-known placeholder "${hit}"`,
      bits: 0,
      remedy,
    };
  }

  const alphabet = estimateAlphabetSize(value);
  const alphabetBits = Math.floor(value.length * Math.log2(Math.max(alphabet, 2)));
  const requiredBits = MIN_SECRET_BYTES * 8;

  // The two readings answer different questions and must NOT be combined with
  // `Math.min`. An earlier version did exactly that and rejected
  // `openssl rand -hex 16` — a real 128-bit secret — because Shannon entropy
  // measured over a single 32-character sample systematically UNDERESTIMATES a
  // truly random string (with 16 symbols in 32 draws the observed distribution
  // is never perfectly uniform, so it reads ~120 bits, just under the floor).
  // Using it as a precise entropy measure therefore fails valid secrets.
  //
  // So: the alphabet reading sets the bar, and Shannon is used only for what it
  // is actually reliable at — spotting pathologically low VARIETY, where the
  // observed distribution collapses far below what the length implies.
  if (alphabetBits < requiredBits) {
    return {
      ok: false,
      reason:
        `${name} is too weak: ~${alphabetBits} bits of entropy, need at least ${requiredBits}. ` +
        `A per-IP lockout does not compensate — it is keyed on IP (so many hosts ` +
        `multiply the attempt budget) and held in memory (so a restart clears it).`,
      bits: alphabetBits,
      remedy,
    };
  }

  // A long string drawn from a wide-looking alphabet but with almost no actual
  // variety ("abababab…" repeated to 40 characters) clears the length bar while
  // being trivially guessable. Real random text lands near ~0.75 of its
  // alphabet reading, so 0.5 separates the two cases with margin.
  const observedBits = shannonBits(value);
  if (observedBits < alphabetBits * 0.5) {
    return {
      ok: false,
      reason:
        `${name} is long but repetitive: only ~${Math.floor(observedBits)} bits of ` +
        `observed variety across ${value.length} characters. It is a pattern, not a random secret.`,
      bits: Math.floor(observedBits),
      remedy,
    };
  }

  return { ok: true, bits: alphabetBits };
}

module.exports = {
  evaluateSecretStrength,
  estimateAlphabetSize,
  shannonBits,
  MIN_SECRET_BYTES,
  FORBIDDEN_SUBSTRINGS,
};
