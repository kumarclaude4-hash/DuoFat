"use strict";

// ── Durable admin brute-force lockout state (S04-L3) ──────────────────────────
//
// WHAT WAS ACTUALLY WRONG (original bug)
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
// FIX HISTORY
//
//   1. Upstash Redis (original durable fix) — single `EVAL` (INCR + conditional
//      PEXPIRE) for exact atomicity. See git history / BUG_TRACKER.md's S04-L3
//      row for that version.
//   2. Cloudflare Workers KV (2026-08-14 migration, superseded same day) — a
//      direct-to-Cloudflare-KV-REST-API client with a non-atomic
//      GET → increment → PUT. This was flagged as a genuine concurrency bug,
//      not an acceptable tradeoff: concurrent failed admin-login attempts
//      could undercount the lockout counter, widening an attacker's guess
//      budget. Removed — see `cloudflareKvStore.js` in git history if you
//      need the exact prior version.
//   3. Cloudflare Durable Object (2026-08-14, current) — see below.
//
// THE CURRENT FIX: a Cloudflare Durable Object, not KV
//
// This module now calls `server/lib/adminLockoutWorkerClient.js`, which
// talks to a Durable Object (`worker/src/adminLockoutDurableObject.js`)
// fronted by the project's existing Cloudflare Worker
// (`worker/src/index.js`'s `/adminLockout` route). Durable Objects guarantee
// that every request routed to the SAME instance is processed strictly one
// at a time (Cloudflare's documented input/output gating) — there is one
// instance per normalized IP (`idFromName(key)`), so the increment-and-check
// that instance performs is a genuine atomic operation. Two concurrent
// failures for the same IP are guaranteed to be recorded as two increments;
// they cannot collapse into one the way they could with the KV version.
// This restores the same atomicity guarantee the original Redis `EVAL` had,
// on Cloudflare's free plan (Durable Objects with the SQLite storage
// backend do not require a paid plan), without introducing a new external
// dependency — it's routed through the Worker this project already deploys.
//
// This module's calls (`recordFailure`/`getStatus`/`reset`) are therefore
// now atomic RPCs to that Durable Object, not raw get/put/delete against a
// key-value store — the atomicity lives entirely on the Worker/Durable
// Object side; this module's only remaining responsibility client-side is
// IP normalization, the local in-memory fallback, and translating a thrown
// error into that fallback (see FAIL-SAFE BEHAVIOR below).
//
// FAIL-SAFE BEHAVIOR (unchanged across every version above)
//
// If the Worker client is not configured (`ADMIN_LOCKOUT_WORKER_URL`/
// `ADMIN_LOCKOUT_WORKER_SECRET` unset) or a call throws (network blip,
// timeout, Worker/Durable Object error), this module falls back to a
// process-local Map with the same semantics as the pre-fix code. This keeps
// the lockout FUNCTIONING (fail safe, not fail open) through an outage, but
// it is explicitly the degraded path: `durableEnabled` and the `onError`
// hook exist so a caller can log/alert when durability has silently
// degraded to process-local, rather than an outage masquerading as "the
// durable fix is working."
//
// A note on the check-then-act gap this does NOT close: `requireAdminAuth`
// in index.js calls `isLocked()` and, separately, `recordFailure()` — two
// concurrent requests that both read "not locked" in the same instant can
// both proceed to compare the token and both then record a failure. Making
// the counter itself atomic (this fix) does not close that outer gap,
// because doing so would require serializing the token comparison itself
// across requests, which the threat model (a network attacker guessing a
// 128-bit-floor `ADMIN_TOKEN` — see `adminSecret.js`) does not justify: the
// worst case is a small, bounded number of extra guesses get through before
// the now-correctly-atomic counter catches up and locks the IP out, not an
// unbounded bypass. What this fix DOES close is the KV version's undercount,
// where the counter itself could permanently lose increments.
//
// KEY DESIGN (unchanged)
//
// Keys are `${KEY_PREFIX}<normalized-ip>`, passed to the Worker as an opaque
// `key` string used only as the Durable Object's `idFromName` input. The
// prefix namespaces this feature away from anything else that might ever
// share the same Worker; normalization (via the caller-supplied
// `normalizeIp`, expected to be `pure.normalizeIpForRateLimit` — see
// S04-M1) is applied before the key is built so the /64-collapsing fix for
// IPv6 rotation stays in effect for the durable store, not just the
// in-memory one it replaces. Values stored are only small records
// (`{count, windowStart}`) inside the Durable Object's own storage — no
// tokens, secrets, or other sensitive data ever leave this process.

const KEY_PREFIX = "duoshield:adminlock:";

// Matches ADMIN_IP_WINDOW_MS / ADMIN_IP_MAX_FAILS in index.js at the time of
// writing. index.js passes its own constants explicitly rather than relying
// on these defaults, so this module never silently drifts out of sync with
// the values displayed to operators in error messages — these exist only so
// the module has a sane default when used/tested standalone.
const DEFAULT_WINDOW_MS = 15 * 60 * 1000;
const DEFAULT_MAX_FAILS = 10;

function buildKey(normalizedIp) {
  return `${KEY_PREFIX}${normalizedIp}`;
}

/**
 * Creates a lockout store.
 *
 * @param {object} [opts]
 * @param {object|null} [opts.client] - A client exposing async
 *   `recordFailure(key, {windowMs, maxFails}) -> {count, locked}`,
 *   `getStatus(key, {windowMs, maxFails}) -> {count, locked}`, and
 *   `reset(key) -> void` — the shape returned by
 *   `adminLockoutWorkerClient.js`'s `createAdminLockoutWorkerClient()` — or
 *   null/undefined if the Worker is not configured, in which case the store
 *   runs entirely on the local fallback Map.
 * @param {number} [opts.windowMs] - Lockout window length, matched to
 *   ADMIN_IP_WINDOW_MS by the caller.
 * @param {number} [opts.maxFails] - Failures within the window that trigger
 *   a lockout, matched to ADMIN_IP_MAX_FAILS by the caller.
 * @param {(ip: string) => string} [opts.normalizeIp] - Applied to the raw IP
 *   before building the key; the caller passes
 *   `pure.normalizeIpForRateLimit` so IPv6 /64 collapsing (S04-M1) still
 *   applies here.
 * @param {() => number} [opts.now] - Injectable clock, for the local
 *   fallback path only (the Durable Object uses its own clock for the
 *   durable path).
 * @param {(op: string, err: Error) => void} [opts.onError] - Called whenever
 *   a Worker/Durable Object call throws and the store falls back to the
 *   local Map for that call, so the caller can log/alert on degraded
 *   durability.
 */
function createAdminLockoutStore({
  client = null,
  windowMs = DEFAULT_WINDOW_MS,
  maxFails = DEFAULT_MAX_FAILS,
  normalizeIp = (ip) => ip,
  now = () => Date.now(),
  onError = (op, err) => console.warn(`[adminLockoutStore] ${op} failed, using local fallback:`, err.message),
} = {}) {
  // Local fallback: identical semantics to the pre-fix in-memory Map. Used
  // whenever the Worker client is unconfigured, or as a per-call degraded
  // path if a Worker/Durable Object call throws. NOT durable, and NOT
  // atomic across concurrent requests to the SAME process (though Node's
  // single-threaded event loop means a single synchronous read-modify-write
  // like the one below cannot itself be interleaved by another request's
  // JS) — see module doc above for why this is an accepted, pre-existing
  // degraded path, not a regression introduced by this fix.
  const localFails = new Map(); // key -> { count, windowStart }

  function localIsLocked(key) {
    const rec = localFails.get(key);
    if (!rec) return false;
    if (now() - rec.windowStart >= windowMs) return false;
    return rec.count >= maxFails;
  }

  function localRecordFailure(key) {
    const t = now();
    const rec = localFails.get(key);
    if (!rec || t - rec.windowStart >= windowMs) {
      localFails.set(key, { count: 1, windowStart: t });
    } else {
      rec.count += 1;
    }
  }

  function localReset(key) {
    localFails.delete(key);
  }

  const durableEnabled = Boolean(client);

  return {
    // Exposed so callers/tests can distinguish "genuinely durable and
    // atomic" from "quietly degraded to local-only" without guessing from
    // behavior alone.
    durableEnabled,

    async isLocked(ip) {
      const key = buildKey(normalizeIp(ip));
      if (!durableEnabled) return localIsLocked(key);
      try {
        const { locked } = await client.getStatus(key, { windowMs, maxFails });
        return locked;
      } catch (err) {
        onError("isLocked", err);
        return localIsLocked(key);
      }
    },

    async recordFailure(ip) {
      const key = buildKey(normalizeIp(ip));
      if (!durableEnabled) {
        localRecordFailure(key);
        return;
      }
      try {
        await client.recordFailure(key, { windowMs, maxFails });
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
      if (!durableEnabled) {
        const rec = localFails.get(key);
        if (!rec || now() - rec.windowStart >= windowMs) return 0;
        return rec.count;
      }
      try {
        const { count } = await client.getStatus(key, { windowMs, maxFails });
        return count;
      } catch (err) {
        onError("count", err);
        const rec = localFails.get(key);
        if (!rec || now() - rec.windowStart >= windowMs) return 0;
        return rec.count;
      }
    },

    async reset(ip) {
      const key = buildKey(normalizeIp(ip));
      // Always clear the local fallback too, even when the Worker client is
      // enabled: if a prior call degraded to local-only (the Worker was
      // down at that moment), stale local state should not outlive a later
      // successful reset.
      localReset(key);
      if (!durableEnabled) return;
      try {
        await client.reset(key);
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
};
