"use strict";

// ── Durable admin brute-force lockout state (S04-L3) ──────────────────────────
//
// WHAT WAS ACTUALLY WRONG
//
// `adminIpFails` (the Map backing `adminIpLocked()`/`recordAdminAuthFailure()`
// in index.js) lived entirely in process memory. Render redeploys on every
// push, restarts on a crash, and can run more than one instance — any of
// those resets every IP's failure count to zero, so the 10-failures/15-minute
// admin lockout (the ONLY brute-force ceiling in front of `ADMIN_TOKEN`,
// per the S05-H1 comment in `lib/adminSecret.js`) never actually accumulates
// across a redeploy or a second instance. An attacker who can trigger (or
// simply wait out) a restart gets a fresh budget of 10 guesses for free,
// indefinitely.
//
// THE FIX
//
// This module is a small, testable abstraction over "is this key locked out"
// / "record a failure for this key" / "clear a key's failures", backed by
// Upstash Redis (a REST-based store, so no persistent TCP connection to
// manage — a good fit for a single Node process making occasional calls).
// `server/index.js` wires it in exactly where `adminIpFails` used to live;
// no caller-visible behavior changes except that the state now survives a
// restart and is shared across instances.
//
// ATOMICITY
//
// `recordFailure()` uses a single `EVAL` (INCR + conditional PEXPIRE) rather
// than separate GET/SET or INCR/EXPIRE round trips. Two round trips would
// race: if two requests both fail at the same moment, both could read
// count-before-increment as the same value, or both land the "this is the
// first failure, set the TTL" branch and reset the window's expiry on every
// single failure instead of only the first — silently extending the lockout
// window forever under sustained load. A single EVAL is atomic with respect
// to every other command Upstash executes against that key, so the
// increment-and-maybe-expire is indivisible. This is the "suitable Upstash
// primitive" called for in the remediation brief; Redis has no distinct
// "INCR that also conditionally sets a TTL" builtin, so EVAL is the correct
// tool rather than a workaround.
//
// This gives exact atomicity for the counter itself. It does NOT give
// exactly-once semantics for the read-then-act sequence in `requireAdminAuth`
// (check `isLocked()`, then separately call `recordFailure()` on a bad
// token) — two requests that both read "not locked" in the same instant can
// both proceed to verify the token and both then record a failure. That is
// the correct, and only, small window: closing it would require holding a
// distributed lock across the token comparison itself, which is not
// justified by the threat model (the counter still lands at the true count
// a request-count later, and the two extra guesses this could account for in
// the worst case are immaterial against a 128-bit-floor token — see
// `adminSecret.js`). Documented here rather than pretended away, per the
// remediation brief's explicit instruction.
//
// FAIL-SAFE BEHAVIOR
//
// If Redis is not configured (`KV_REST_API_URL`/`KV_REST_API_TOKEN` unset) or
// a call throws (network blip, Upstash outage), this module falls back to a
// process-local Map with the same semantics as the pre-fix code. This keeps
// the lockout FUNCTIONING (fail safe, not fail open) through an outage, but
// it is explicitly the degraded path: `redisEnabled` and the `onError` hook
// exist so a caller can log/alert when durability has silently degraded to
// process-local, rather than an outage masquerading as "the durable fix is
// working." A fallback that quietly looks identical to the real fix would
// violate the remediation brief's requirement not to present degraded
// protection as equivalent to durable protection.
//
// KEY DESIGN
//
// Keys are `${KEY_PREFIX}<normalized-ip>`. The prefix namespaces this
// feature's keys away from anything else that might ever share the same
// Redis database; normalization (via the caller-supplied `normalizeIp`,
// expected to be `pure.normalizeIpForRateLimit` — see S04-M1) is applied
// before the key is built so the /64-collapsing fix for IPv6 rotation stays
// in effect for the durable store, not just the in-memory one it replaces.
// Values stored are only small integers (failure counts) with a bounded TTL
// — no tokens, secrets, or other sensitive data ever touch Redis here.

const KEY_PREFIX = "duoshield:adminlock:";

// Matches ADMIN_IP_WINDOW_MS / ADMIN_IP_MAX_FAILS in index.js at the time of
// writing. index.js passes its own constants explicitly rather than relying
// on these defaults, so this module never silently drifts out of sync with
// the values displayed to operators in error messages — these exist only so
// the module has a sane default when used/tested standalone.
const DEFAULT_WINDOW_MS = 15 * 60 * 1000;
const DEFAULT_MAX_FAILS = 10;

// Atomically increments the failure counter and, only on the very first
// failure of a fresh window (count === 1 immediately after the INCR), sets a
// millisecond TTL equal to the window length. Every subsequent failure in
// the same window increments without touching the TTL, so the window's
// expiry is anchored to the first failure, not pushed out by later ones.
const INCR_AND_MAYBE_EXPIRE_SCRIPT = `
local count = redis.call("INCR", KEYS[1])
if count == 1 then
  redis.call("PEXPIRE", KEYS[1], ARGV[1])
end
return count
`;

function buildKey(normalizedIp) {
  return `${KEY_PREFIX}${normalizedIp}`;
}

/**
 * Creates a lockout store.
 *
 * @param {object} [opts]
 * @param {object|null} [opts.redis] - An @upstash/redis `Redis` client
 *   instance (must expose `.get`, `.eval`, `.del`), or null/undefined if
 *   Redis is not configured — the store then runs entirely on the local
 *   fallback Map.
 * @param {number} [opts.windowMs] - Lockout window length, matched to
 *   ADMIN_IP_WINDOW_MS by the caller.
 * @param {number} [opts.maxFails] - Failures within the window that trigger
 *   a lockout, matched to ADMIN_IP_MAX_FAILS by the caller.
 * @param {(ip: string) => string} [opts.normalizeIp] - Applied to the raw IP
 *   before building the Redis key; the caller passes
 *   `pure.normalizeIpForRateLimit` so IPv6 /64 collapsing (S04-M1) still
 *   applies here.
 * @param {(op: string, err: Error) => void} [opts.onError] - Called whenever
 *   a Redis call throws and the store falls back to the local Map for that
 *   call, so the caller can log/alert on degraded durability.
 */
function createAdminLockoutStore({
  redis = null,
  windowMs = DEFAULT_WINDOW_MS,
  maxFails = DEFAULT_MAX_FAILS,
  normalizeIp = (ip) => ip,
  onError = (op, err) => console.warn(`[adminLockoutStore] ${op} failed, using local fallback:`, err.message),
} = {}) {
  // Local fallback: identical semantics to the pre-fix in-memory Map. Used
  // whenever Redis is unconfigured, or as a per-call degraded path if a
  // Redis operation throws. NOT durable — see module doc above.
  const localFails = new Map(); // key -> { count, windowStart }

  function localIsLocked(key) {
    const rec = localFails.get(key);
    if (!rec) return false;
    if (Date.now() - rec.windowStart >= windowMs) return false;
    return rec.count >= maxFails;
  }

  function localRecordFailure(key) {
    const now = Date.now();
    const rec = localFails.get(key);
    if (!rec || now - rec.windowStart >= windowMs) {
      localFails.set(key, { count: 1, windowStart: now });
    } else {
      rec.count += 1;
    }
  }

  function localReset(key) {
    localFails.delete(key);
  }

  const redisEnabled = Boolean(redis);

  return {
    // Exposed so callers/tests can distinguish "genuinely durable" from
    // "quietly degraded to local-only" without guessing from behavior alone.
    redisEnabled,

    async isLocked(ip) {
      const key = buildKey(normalizeIp(ip));
      if (!redisEnabled) return localIsLocked(key);
      try {
        const count = await redis.get(key);
        return Number(count || 0) >= maxFails;
      } catch (err) {
        onError("isLocked", err);
        return localIsLocked(key);
      }
    },

    async recordFailure(ip) {
      const key = buildKey(normalizeIp(ip));
      if (!redisEnabled) {
        localRecordFailure(key);
        return;
      }
      try {
        await redis.eval(INCR_AND_MAYBE_EXPIRE_SCRIPT, [key], [String(windowMs)]);
      } catch (err) {
        onError("recordFailure", err);
        localRecordFailure(key);
      }
    },

    // Current failure count for `ip` in the active window, or 0 if none.
    // Diagnostic/audit-log use only (e.g. "how many failures got us here") —
    // requireAdminAuth's actual lockout decision goes through isLocked(),
    // not this.
    async count(ip) {
      const key = buildKey(normalizeIp(ip));
      if (!redisEnabled) {
        const rec = localFails.get(key);
        if (!rec || Date.now() - rec.windowStart >= windowMs) return 0;
        return rec.count;
      }
      try {
        const value = await redis.get(key);
        return Number(value || 0);
      } catch (err) {
        onError("count", err);
        const rec = localFails.get(key);
        if (!rec || Date.now() - rec.windowStart >= windowMs) return 0;
        return rec.count;
      }
    },

    async reset(ip) {
      const key = buildKey(normalizeIp(ip));
      // Always clear the local fallback too, even when Redis is enabled: if a
      // prior call degraded to local-only (Redis was down at that moment),
      // stale local state should not outlive a later successful reset.
      localReset(key);
      if (!redisEnabled) return;
      try {
        await redis.del(key);
      } catch (err) {
        onError("reset", err);
      }
    },

    // Test/ops helper only — not used by index.js.
    _localSize() {
      return localFails.size;
    },
  };
}

module.exports = {
  createAdminLockoutStore,
  buildKey,
  DEFAULT_WINDOW_MS,
  DEFAULT_MAX_FAILS,
  INCR_AND_MAYBE_EXPIRE_SCRIPT,
};
