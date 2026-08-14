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
// THE FIX (original, Upstash Redis)
//
// This module was originally backed by Upstash Redis, using a single `EVAL`
// (INCR + conditional PEXPIRE) for exact atomicity of the counter. See git
// history / BUG_TRACKER.md's S04-L3 row for that version.
//
// MIGRATION (2026-08-14): Upstash Redis → Cloudflare Workers KV
//
// This module now runs on Cloudflare Workers KV (`server/lib/cloudflareKvStore.js`)
// instead of Redis, called directly over Cloudflare's REST API from this
// Node process (no Worker/Durable Object in the request path).
//
// ATOMICITY — WHAT CHANGED
//
// Cloudflare KV has no INCR, no CAS write, and no server-side scripting
// equivalent to Redis EVAL: only GET/PUT/DELETE on a whole value. The
// counter here is therefore now a plain read-modify-write —
// GET the record, compute count+1 in this process, PUT it back — which is
// NOT atomic. Two requests that fail at the same instant can both read the
// same pre-increment count and both write count+1, silently losing one
// increment (an undercount, never an overcount).
//
// This is a deliberate, accepted tradeoff, not an oversight:
//   - The threat model here is a network attacker guessing `ADMIN_TOKEN`
//     (a 128-bit-floor secret — see `adminSecret.js`), not a sophisticated
//     attacker who can reliably win a sub-request-latency race on every one
//     of the ~10 attempts needed to matter. Losing an occasional increment
//     to the race widens the effective guess budget by at most a handful of
//     attempts in the worst case, which is immaterial against that entropy
//     floor. This is the same class of tradeoff already accepted for the
//     check-then-act race between `isLocked()` and `recordFailure()` in
//     `requireAdminAuth` (two requests that both read "not locked" in the
//     same instant can both proceed and both then record a failure) — no
//     store, Redis included, ever closed that gap either, because doing so
//     would require a distributed lock across the token comparison itself,
//     which the threat model does not justify.
//   - This project already has an existing, explicit precedent for this
//     exact tradeoff: the Cloudflare Worker's own KV-backed rate counters
//     (`worker/src/index.js`) are documented as best-effort/non-atomic,
//     with "true atomicity would need Durable Objects, not provisioned for
//     this project." This migration follows that same precedent rather
///     than introducing a new one.
//   - The alternative (a Durable Object to serialize the increment) would
//     restore exact atomicity but requires the Workers Paid plan and a new
//     piece of infrastructure (the first Durable Object in this project)
//     purely to protect a counter whose failure mode is "an attacker gets a
//     few extra guesses at a 128-bit token," not "an attacker bypasses the
//     lockout outright." Not introduced here; if the threat model changes
//     (e.g. `ADMIN_TOKEN`'s entropy floor is ever lowered), revisit this.
//
// TTL — WHAT CHANGED
//
// Cloudflare KV's `expiration_ttl` has a hard 60-second minimum and, unlike
// Redis PEXPIRE, is not something this code can "leave alone" on a write —
// every PUT either sets a TTL or the key becomes permanent. So the window's
// start time is now stored IN the record (`{ count, windowStart }`) as the
// source of truth, and `isLocked()`/`count()` compute "is this window still
// active" themselves from `windowStart`, exactly like the local fallback
// below always has. The KV `expiration_ttl` sent on every PUT is only a
// cleanup backstop (so abandoned keys eventually vanish from the namespace)
// — never the authority on whether a record is still valid. This means a
// key can physically persist in KV slightly past its logical window (e.g.
// if remaining time was clamped up to Cloudflare's 60s floor) without any
// effect on correctness: `isLocked()` will still correctly say "not locked"
// once `windowMs` has elapsed since `windowStart`, regardless of what KV's
// own TTL clock says.
//
// FAIL-SAFE BEHAVIOR (unchanged)
//
// If Cloudflare KV is not configured (`CLOUDFLARE_ACCOUNT_ID`/
// `CLOUDFLARE_API_TOKEN`/`CLOUDFLARE_KV_NAMESPACE_ID` unset) or a call
// throws (network blip, timeout, Cloudflare API error), this module falls
// back to a process-local Map with the same semantics as the pre-fix code.
// This keeps the lockout FUNCTIONING (fail safe, not fail open) through an
// outage, but it is explicitly the degraded path: `kvEnabled` and the
// `onError` hook exist so a caller can log/alert when durability has
// silently degraded to process-local, rather than an outage masquerading as
// "the durable fix is working."
//
// KEY DESIGN (unchanged)
//
// Keys are `${KEY_PREFIX}<normalized-ip>`. The prefix namespaces this
// feature's keys away from anything else that might ever share the same KV
// namespace; normalization (via the caller-supplied `normalizeIp`, expected
// to be `pure.normalizeIpForRateLimit` — see S04-M1) is applied before the
// key is built so the /64-collapsing fix for IPv6 rotation stays in effect
// for the durable store, not just the in-memory one it replaces. Values
// stored are only small JSON records (`{count, windowStart}`) with a bounded
// TTL — no tokens, secrets, or other sensitive data ever touch KV here.

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

// Parses a raw KV value into `{ count, windowStart }`, or returns `null` for
// a missing/malformed value. A malformed value (corrupted write, unexpected
// format from some future caller) degrades to "treat as absent" rather than
// throwing — a corrupted lockout record must never crash admin auth, and
// "absent" is the fail-safe direction (worst case: one IP's window resets
// early, not a lockout that can never be checked).
function parseRecord(raw) {
  if (raw === null || raw === undefined) return null;
  try {
    const parsed = JSON.parse(raw);
    if (parsed && typeof parsed.count === "number" && typeof parsed.windowStart === "number") {
      return parsed;
    }
  } catch {
    // Not valid JSON — fall through to null below.
  }
  return null;
}

/**
 * Creates a lockout store.
 *
 * @param {object} [opts]
 * @param {object|null} [opts.kv] - A client exposing `get(key)` (resolves to
 *   the string value or `null` if absent), `put(key, value, {expirationTtl})`,
 *   and `delete(key)` — the shape returned by
 *   `cloudflareKvStore.js`'s `createCloudflareKvClient()` — or null/undefined
 *   if Cloudflare KV is not configured, in which case the store runs
 *   entirely on the local fallback Map.
 * @param {number} [opts.windowMs] - Lockout window length, matched to
 *   ADMIN_IP_WINDOW_MS by the caller.
 * @param {number} [opts.maxFails] - Failures within the window that trigger
 *   a lockout, matched to ADMIN_IP_MAX_FAILS by the caller.
 * @param {(ip: string) => string} [opts.normalizeIp] - Applied to the raw IP
 *   before building the KV key; the caller passes
 *   `pure.normalizeIpForRateLimit` so IPv6 /64 collapsing (S04-M1) still
 *   applies here.
 * @param {() => number} [opts.now] - Injectable clock, for tests.
 * @param {(op: string, err: Error) => void} [opts.onError] - Called whenever
 *   a KV call throws and the store falls back to the local Map for that
 *   call, so the caller can log/alert on degraded durability.
 */
function createAdminLockoutStore({
  kv = null,
  windowMs = DEFAULT_WINDOW_MS,
  maxFails = DEFAULT_MAX_FAILS,
  normalizeIp = (ip) => ip,
  now = () => Date.now(),
  onError = (op, err) => console.warn(`[adminLockoutStore] ${op} failed, using local fallback:`, err.message),
} = {}) {
  // Local fallback: identical semantics to the pre-fix in-memory Map. Used
  // whenever KV is unconfigured, or as a per-call degraded path if a KV
  // operation throws. NOT durable — see module doc above.
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

  const kvEnabled = Boolean(kv);

  // Seconds remaining in the record's window, used only as a cleanup
  // backstop TTL on the KV write — see "TTL — WHAT CHANGED" above. Can be
  // smaller than Cloudflare's 60s floor near the end of a window; the KV
  // client clamps that up, which is safe because application-level window
  // checks (not this TTL) decide validity.
  function remainingTtlSeconds(windowStart) {
    return Math.ceil((windowStart + windowMs - now()) / 1000);
  }

  return {
    // Exposed so callers/tests can distinguish "genuinely durable" from
    // "quietly degraded to local-only" without guessing from behavior alone.
    kvEnabled,

    async isLocked(ip) {
      const key = buildKey(normalizeIp(ip));
      if (!kvEnabled) return localIsLocked(key);
      try {
        const rec = parseRecord(await kv.get(key));
        if (!rec) return false;
        if (now() - rec.windowStart >= windowMs) return false;
        return rec.count >= maxFails;
      } catch (err) {
        onError("isLocked", err);
        return localIsLocked(key);
      }
    },

    async recordFailure(ip) {
      const key = buildKey(normalizeIp(ip));
      if (!kvEnabled) {
        localRecordFailure(key);
        return;
      }
      try {
        const existing = parseRecord(await kv.get(key));
        const t = now();
        const next =
          !existing || t - existing.windowStart >= windowMs
            ? { count: 1, windowStart: t }
            : { count: existing.count + 1, windowStart: existing.windowStart };
        await kv.put(key, JSON.stringify(next), { expirationTtl: remainingTtlSeconds(next.windowStart) });
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
      if (!kvEnabled) {
        const rec = localFails.get(key);
        if (!rec || now() - rec.windowStart >= windowMs) return 0;
        return rec.count;
      }
      try {
        const rec = parseRecord(await kv.get(key));
        if (!rec || now() - rec.windowStart >= windowMs) return 0;
        return rec.count;
      } catch (err) {
        onError("count", err);
        const rec = localFails.get(key);
        if (!rec || now() - rec.windowStart >= windowMs) return 0;
        return rec.count;
      }
    },

    async reset(ip) {
      const key = buildKey(normalizeIp(ip));
      // Always clear the local fallback too, even when KV is enabled: if a
      // prior call degraded to local-only (KV was down at that moment),
      // stale local state should not outlive a later successful reset.
      localReset(key);
      if (!kvEnabled) return;
      try {
        await kv.delete(key);
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
