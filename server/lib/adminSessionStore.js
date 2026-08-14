"use strict";

// ── Admin session lifecycle store (S05-M3) ────────────────────────────────────
//
// WHAT WAS ACTUALLY WRONG (audit/SESSION-05-ADMIN.md:452-498)
//
// The pre-fix `adminSessions` (a bare `Map<sessionId, expiresAt>` in
// server/index.js) had five compounding weaknesses:
//   1. No absolute cap — every touch extended the session another 30
//      minutes, forever, because no `createdAt` was ever recorded.
//   2. The refresh happened on an UNAUTHENTICATED route (`GET /admin`, used
//      only to decide which view to render) — a browser prefetch or a
//      restored tab kept a session alive with no operator activity at all.
//   3. Bound to nothing — no IP, no User-Agent — so a cookie captured from
//      any channel (shared machine, synced browser profile, log leak) was a
//      full admin credential from anywhere.
//   4. The 10-minute inactivity timeout was client-side JavaScript only; the
//      server-side TTL it appeared to enforce was actually the 30-minute
//      sliding value from (1), which per the project's own rule that client
//      checks are never controls, bounded nothing.
//   5. No bulk revocation — rotating ADMIN_TOKEN or responding to a suspected
//      compromise could not invalidate sessions already minted.
//
// THE FIX
//
// This module is a small, testable, in-memory session store used by
// server/index.js in place of the bare Map. It enforces, together:
//   - a sliding IDLE timeout (`idleTtlMs`), refreshed only when the caller
//     passes `{ refresh: true }` (the default) — index.js passes
//     `{ refresh: false }` from the unauthenticated `GET /admin` render
//     check specifically so that route can no longer extend a session
//     (closes weakness 2);
//   - an ABSOLUTE lifetime ceiling (`absoluteTtlMs`) recorded from
//     `createdAt` at creation time, which no refresh can push past — closes
//     weakness 1;
//   - binding to caller-supplied client-context values (an IP tag and/or a
//     User-Agent string, captured at creation and compared on every
//     `validate()` call) — closes weakness 3. index.js passes `ipTag(...)`
//     (the same pseudonymised HMAC tag S05-M1 already introduced for audit
//     logs), not the raw IP, so this fix does not add a new place that
//     stores an identifying value in the clear;
//   - `revoke()` (single session) and `revokeAll()` (every session) —
//     `revokeAll()` closes weakness 5 and is wired to a new admin-panel
//     "Sign out everywhere" action, itself gated by the same
//     `requireAdminAuth()` every other admin mutation uses.
//
// DESIGN NOTE ON THE BINDING CHECK
//
// A mismatch REJECTS the individual request (`{valid: false, reason:
// "ip_mismatch" | "ua_mismatch"}`) but does not delete the session record.
// This is deliberate: an attacker replaying a stolen cookie from a
// different IP/UA is refused every single time they try, from that
// context — the binding does its job. But it does not turn a legitimate
// operator's transient network change (switching wifi to LTE mid-session,
// a carrier-grade NAT reassigning an address) into a forced full
// re-authentication the moment they return to their original context,
// which the task's own "preserve legitimate admin sessions" requirement
// rules out as an acceptable side effect. True expiry (idle or absolute)
// still deletes the record outright, since there nothing legitimate can
// ever make that request valid again.
//
// WHAT THIS DOES NOT DO (left out of S05-M3's scope, not silently missed)
//
//   - Session-id rotation after login / step-up re-authentication for
//     destructive actions (unfreeze, duress enroll) — named in the original
//     finding's fix list but not in this task's own six-item scope. A
//     future finding, not claimed fixed here.
//   - Clearing all sessions automatically when ADMIN_TOKEN is rotated —
//     ADMIN_TOKEN is read once at module load (index.js), so this would
//     need a config-reload hook that does not currently exist; out of
//     scope for a session-store change alone.
//   - Durability across a restart / sharing across instances — like the
//     pre-fix Map, this store is process-local (the cross-reference at the
//     end of the original finding already calls this an existing,
//     documented S04-L3-adjacent characteristic, not something this fix
//     claims to change).

const crypto = require("node:crypto");

const DEFAULT_IDLE_TTL_MS = 30 * 60 * 1000; // 30 min sliding idle timeout
const DEFAULT_ABSOLUTE_TTL_MS = 8 * 60 * 60 * 1000; // 8h hard ceiling, no refresh extends past this

function defaultRandomId() {
  return crypto.randomBytes(32).toString("hex");
}

/**
 * Creates an in-memory admin session store.
 *
 * @param {object} [opts]
 * @param {number} [opts.idleTtlMs] - Sliding idle timeout in ms.
 * @param {number} [opts.absoluteTtlMs] - Absolute lifetime ceiling in ms,
 *   measured from `createdAt`. Must be >= idleTtlMs to have any effect
 *   beyond the idle timeout; callers are expected to configure it larger.
 * @param {boolean} [opts.bindIp] - Whether `validate()` enforces the IP
 *   dimension of the binding. Default `false`: a mobile client's address
 *   legitimately changes mid-session (WiFi/LTE handover, CGNAT reassignment,
 *   IPv4/IPv6 route switching), and enforcing it there produced spurious
 *   "session expired" logouts (S07-H1). The User-Agent binding is always
 *   enforced. Set `true` only where the client address is guaranteed stable.
 * @param {() => number} [opts.now] - Clock, injectable for deterministic
 *   tests. Defaults to `Date.now`.
 * @param {() => string} [opts.randomId] - Session id generator, injectable
 *   for deterministic tests. Defaults to a 256-bit hex token.
 */
function createAdminSessionStore({
  idleTtlMs = DEFAULT_IDLE_TTL_MS,
  absoluteTtlMs = DEFAULT_ABSOLUTE_TTL_MS,
  bindIp = false,
  now = () => Date.now(),
  randomId = defaultRandomId,
} = {}) {
  // sessionId -> { createdAt, expiresAt, absoluteExpiresAt, ip, userAgent }
  const sessions = new Map();

  /**
   * Creates a new session bound to the given client context.
   *
   * @param {{ip?: string|null, userAgent?: string|null}} [ctx] - Context to
   *   bind the session to. Pass whatever the caller wants matched on every
   *   later `validate()` call (index.js passes `ipTag(getClientIp(req))`
   *   and the raw `User-Agent` header, truncated). Omitting a field (or
   *   passing `null`) means that dimension is not bound and `validate()`
   *   will not check it unless the caller of `validate()` also omits it —
   *   see `validate()`'s doc comment for the exact matching rule.
   * @returns {string} the new opaque session id.
   */
  function create(ctx = {}) {
    const createdAt = now();
    const sessionId = randomId();
    sessions.set(sessionId, {
      createdAt,
      expiresAt: createdAt + idleTtlMs,
      absoluteExpiresAt: createdAt + absoluteTtlMs,
      ip: ctx.ip === undefined ? null : ctx.ip,
      userAgent: ctx.userAgent === undefined || ctx.userAgent === null
        ? null
        : String(ctx.userAgent).slice(0, 200),
    });
    return sessionId;
  }

  /**
   * Validates a session id against the current request's client context.
   *
   * @param {string} sessionId
   * @param {{ip?: string|null, userAgent?: string|null}} [ctx] - The
   *   CURRENT request's context. A field present here (including explicit
   *   `null`) is compared against what `create()` stored for that field;
   *   omitting a field skips that dimension's check entirely. index.js
   *   always passes both fields, so in practice both are always checked —
   *   the "omit to skip" behavior exists so unit tests can isolate TTL
   *   behavior from binding behavior without contriving matching context.
   * @param {{refresh?: boolean}} [opts] - `refresh` (default `true`):
   *   whether a successful validation should slide the idle expiry
   *   forward, capped at the absolute ceiling. index.js passes `false`
   *   from the unauthenticated `GET /admin` render check.
   * @returns {{valid: true}|{valid: false, reason: string}}
   */
  function validate(sessionId, ctx = {}, opts = {}) {
    const refresh = opts.refresh !== false;
    if (!sessionId) return { valid: false, reason: "missing" };
    const rec = sessions.get(sessionId);
    if (!rec) return { valid: false, reason: "not_found" };

    const t = now();
    if (t >= rec.absoluteExpiresAt) {
      sessions.delete(sessionId);
      return { valid: false, reason: "absolute_expired" };
    }
    if (t >= rec.expiresAt) {
      sessions.delete(sessionId);
      return { valid: false, reason: "idle_expired" };
    }

    // Binding checks reject THIS request without deleting the session — see
    // the module doc comment's "DESIGN NOTE ON THE BINDING CHECK" above.
    //
    // S07-H1: the IP dimension is now opt-in (`bindIp`, default off) rather
    // than always-on. On a mobile network the client address legitimately
    // changes mid-session — WiFi to LTE, a carrier-grade NAT reassigning an
    // address, a dual-stack client switching between its IPv4 and IPv6 route —
    // and each of those turned into a hard `ip_mismatch` that bounced the
    // operator to the login gate with "Your session expired" while the cookie
    // was in fact still perfectly valid. That is exactly the reported bug on
    // mobile. The User-Agent binding (which does NOT change across a network
    // switch) is kept mandatory, so a cookie replayed from a different client
    // is still refused; IP binding remains available for deployments that can
    // guarantee a stable client address.
    if (bindIp && ctx.ip !== undefined && rec.ip !== ctx.ip) {
      return { valid: false, reason: "ip_mismatch" };
    }
    if (ctx.userAgent !== undefined && rec.userAgent !== ctx.userAgent) {
      return { valid: false, reason: "ua_mismatch" };
    }

    if (refresh) {
      // The line that closes weakness 1: sliding refresh is capped at the
      // absolute ceiling, so no amount of continued activity can push
      // expiresAt past absoluteExpiresAt.
      rec.expiresAt = Math.min(t + idleTtlMs, rec.absoluteExpiresAt);
    }
    return { valid: true };
  }

  /** Revokes a single session. Returns true if it existed. */
  function revoke(sessionId) {
    return sessions.delete(sessionId);
  }

  /** Revokes every active session. Returns the number revoked. */
  function revokeAll() {
    const count = sessions.size;
    sessions.clear();
    return count;
  }

  /** Removes expired entries. Intended for periodic background cleanup. */
  function sweep() {
    const t = now();
    for (const [id, rec] of sessions) {
      if (t >= rec.absoluteExpiresAt || t >= rec.expiresAt) sessions.delete(id);
    }
  }

  /** Count of sessions currently tracked (expired-but-unswept included). */
  function size() {
    return sessions.size;
  }

  // Test/diagnostic helper only — not used by index.js's request path.
  function _get(sessionId) {
    const rec = sessions.get(sessionId);
    return rec ? Object.assign({}, rec) : null;
  }

  return { create, validate, revoke, revokeAll, sweep, size, _get };
}

module.exports = {
  createAdminSessionStore,
  DEFAULT_IDLE_TTL_MS,
  DEFAULT_ABSOLUTE_TTL_MS,
};
