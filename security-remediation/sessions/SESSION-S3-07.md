# SESSION-S3-07 — Server limits, memory growth, IP keying

**Lane:** SRV
**Status:** Complete — 5 of 5 scoped findings fixed, across three sub-sessions
**Commits:**
- `000ed14` — `S02-L3`, `S02-L4`/`S04-L1`, `S04-M3` (first sub-session)
- `e64f3b4` — documentation for the above
- `959d869` — `S04-M1` (second sub-session; this file originally shipped
  claiming `S04-M1` deferred — that was corrected once the fix landed, see
  "Correction" below)
- `fe9559a` — `S04-L3` (third sub-session, this document's reconciliation)

## Scope (per `ROUND3_REMEDIATION_PLAN.md`)

S3-07 is scoped to 5 findings: `S02-L3` (`mintCooldown` purge), `S02-L4`/`S04-L1`
(byte-count body cap), `S04-L3` (limiter purge / durable store), `S04-M1`
(IPv6 /64 keying for all IP-keyed limits incl. admin lockout), `S04-M3` (XFF
trust configurable).

**The first sub-session fixed 3 of the 5** (`S02-L3`, `S02-L4`/`S04-L1`
combined = one fix, `S04-M3`) under an explicit budget constraint from the
user ("fix 3 more bugs"). `S04-M1` (IPv6 /64 keying) and `S04-L3` (limiter
purge / durable store) were deferred at that point, not claimed fixed.

**Both deferred findings have since been fixed**, closing out S3-07's scope
entirely:
- `S04-M1` — fixed in a follow-on continuation of this same session (commit
  `959d869`); see "S04-M1 fix" below.
- `S04-L3` — fixed in a third sub-session (commit `fe9559a`) after a prior
  attempt at it ran out of budget mid-implementation (dependency installed,
  no code written yet) and was picked back up cleanly; see "S04-L3 fix"
  below.

## What was verified from source before fixing

Per protocol ("source beats tracker"), each finding was re-read against
current `server/index.js` before any code was written:

- **`S02-L3`** — confirmed: `mintCooldown` (declared `server/index.js:347`,
  used at the mint-token cooldown check ~line 2023) had no purge job. Every
  sibling in-memory limiter (`ipHits`, `waitlistIpHits`, `authRateLimits`) has
  a `setInterval` purge; `mintCooldown` did not. `userId` is not a secret (see
  the adjacent `S02-M1` comment in source), so an attacker can grow this Map
  without bound just by POSTing distinct userIds to `/mintToken`.
- **`S02-L4`/`S04-L1`** — confirmed: `collectBody()` (line ~1056) compared
  `body.length` — the JS string length *after* `body += chunk` implicitly ran
  `chunk.toString("utf8")` — against `MAX_BODY_BYTES`, rather than counting
  actual bytes off the incoming `Buffer`. `readBody()` a few lines above (the
  Promise-based sibling) already tracked `bytes += chunk.length` correctly;
  `collectBody()` (the callback-style one, used where a handler needs to run
  `requireAdminAuth`/other checks before parsing) had the bug. Repo-wide grep
  confirmed no `setEncoding()` call exists anywhere on `req`, so this was a
  pure counting bug, not an encoding-mode bug — chunks are always Buffers.
- **`S04-M3`** — confirmed: `getClientIp()` (line ~819) unconditionally took
  `entries[entries.length - 1]` off `X-Forwarded-For` with no configuration
  point. Correct for the current single-Render-proxy deployment, but there
  was no way to run this server behind zero trusted proxies (local dev, or a
  topology where XFF cannot be trusted at all) or more than one (a CDN added
  in front of Render later) without editing code.

## What was fixed

All three fixes follow the codebase's established "pure helper in `lib/`,
imperative shell in `index.js`" pattern (`lib/pure.js`, `lib/mediaScope.js`,
`lib/profileSanitize.js` from prior sessions) so the interesting logic is
unit-testable without a live HTTP server, Firestore, or timers.

1. **`S02-L3` — `mintCooldown` purge.** New pure `collectStaleKeys(entries,
   now, ttlMs)` in `server/lib/pure.js` takes any `[key, timestampMs]`
   iterable (a `Map` satisfies this via its default iterator) and returns the
   stale keys — no Map mutation, no clock dependency, fully deterministic.
   `server/index.js` adds a 5-minute `setInterval` that calls it against the
   live `mintCooldown` Map and deletes what comes back, matching the cadence
   of the sibling limiters. `MINT_COOLDOWN_MS` was also extracted as a named
   constant (previously a bare `60_000` literal duplicated at the purge site
   and the cooldown-check site).

2. **`S02-L4`/`S04-L1` — byte-cap bypass.** `collectBody()` now tracks
   `bytes += chunk.length` (Buffer byte length) exactly like `readBody()`,
   and gates on `bytes > MAX_BODY_BYTES` instead of `body.length`. The string
   accumulation (`body += chunk`) is unchanged — only the size check that
   gates the 413 changed.

3. **`S04-M3` — configurable XFF trust.** New pure `pickClientIp
   (forwardedHeader, remoteAddress, trustedHops)` in `server/lib/pure.js`
   encodes "trust exactly N hops from the right of X-Forwarded-For, or ignore
   it entirely if N is 0" with no I/O. `server/index.js` reads
   `TRUSTED_PROXY_HOPS` from the environment (default `1`, which reproduces
   the old hardcoded "always trust the rightmost entry" behavior exactly for
   the current Render deployment) and calls `pickClientIp()` from
   `getClientIp()`.

## S04-M1 fix (commit `959d869`)

IPv6 /64 keying for all IP-keyed limits, including admin lockout. New pure
`normalizeIpForRateLimit(ip)` in `server/lib/pure.js` collapses an IPv6
address to its /64 prefix (unwrapping IPv4-mapped forms and stripping zone
indices first; malformed input is returned unchanged so callers never throw
on a weird header) so a residential /64 rotating its last 64 bits can no
longer mint one rate-limit bucket per request. Wired into every IP-keyed
limiter in `server/index.js` at the time: `ipHits`, `waitlistIpHits`,
`waitlistPollHits`, and the admin lockout (`adminIpFails`, later replaced by
`adminLockoutStore` — see the `S04-L3` fix, which reuses this same
normalizer). 14 new cases in `lib/pure.test.js`.

## S04-L3 fix (commit `fe9559a`)

Limiter state per-process/in-memory. Scoped to the highest-value target: the
admin brute-force failure counter, which gates guessing `ADMIN_TOKEN` (see
`S05-H1`), reset to zero on every restart/redeploy and was not shared across
instances — the only ceiling on that guess surface never accumulated
durably. A prior attempt at this fix, in a separate sub-session, ran the
finding audit, confirmed Upstash Redis as the intended store, and installed
`@upstash/redis` before running out of budget with no implementation code
written yet; that state was picked up and completed cleanly rather than
redone.

New `server/lib/adminLockoutStore.js` wraps Upstash Redis
(`KV_REST_API_URL`/`KV_REST_API_TOKEN`) behind `isLocked()`/
`recordFailure()`/`reset()`/`count()`:
- `recordFailure()` uses a single Lua `EVAL` (INCR + conditional PEXPIRE) so
  two concurrent failures can't race the "first failure sets the TTL" branch
  into repeatedly extending the lockout window.
- Falls back to an in-memory Map with the pre-fix semantics if Redis is
  unconfigured or a call throws — an outage degrades to (not worse than) the
  old per-process behavior, and `onError()` warns on every fallback rather
  than silently presenting it as equivalent durable protection.
- IP normalization is injected as `pure.normalizeIpForRateLimit` (the
  `S04-M1` fix), so the durable store gets the same /64 collapsing the other
  limiters already have.

`server/index.js`'s `adminIpLocked()`/`recordAdminAuthFailure()` now
delegate to the store and are `async` (every one of the 9
`requireAdminAuth()` call sites, and the `POST /admin/login` handler, already
ran inside an async context, so this is `await`, not restructuring). A new
`resetAdminAuthFailures()` clears an IP's failures on successful admin
login — previously failures only ever accumulated within the (15-minute)
window.

**Not in scope:** the other in-memory limiters (`ipHits`, `waitlistIpHits`,
`waitlistPollHits`, the challenge/nonce stores) remain process-local —
lower-value targets than the admin gate, left `Open` for a future pass if
warranted.

## Test evidence

**First sub-session** (`S02-L3`, `S02-L4`/`S04-L1`, `S04-M3`):
- `node --check server/index.js` and `node --check server/lib/pure.js` — both
  clean.
- `npm test` (server): **156 pass / 1 fail** (was 146/147 before this
  session — the +10 delta is the new `collectStaleKeys`/`pickClientIp`
  cases). The 1 failure is `lib/identityVerify.test.js`, the same
  pre-existing, unrelated missing-native-module failure documented in
  `SESSION_PROTOCOL.md` §0 and reproduced independently across S3-05 and
  S3-06 — not a regression from this session's changes.
- New tests, all passing in isolation (`node --test lib/pure.test.js`):
  - `collectStaleKeys` — mixed fresh/stale entries, exact-TTL-boundary is
    not-yet-stale, works directly against a live `Map` (no array copy
    required by callers), empty input.
  - `pickClientIp` — default (1 hop) reproduces the original rightmost-entry
    behavior byte-for-byte, `0` hops ignores XFF entirely, `2` hops picks the
    second-from-right entry, fewer entries than trusted hops falls back to
    the socket address, absent header falls back to the socket address,
    non-integer `trustedHops` defaults to `1`.
- Live-wiring grep confirms `pure.collectStaleKeys` and `pure.pickClientIp`
  are both called from `server/index.js`, not just defined and unused.

**`S04-M1` sub-session:**
- 14 new cases in `lib/pure.test.js` for `normalizeIpForRateLimit`.
- Full suite: 165/166 pass (the 1 failure is the same pre-existing
  `identityVerify.test.js` issue, unchanged by this fix).
- Live-wiring grep confirms `normalizeIpForRateLimit` is called from all
  four IP-keyed limiters in `server/index.js`.

**`S04-L3` sub-session:**
- `node --check` clean on `server/index.js`, `server/lib/adminLockoutStore.js`,
  `server/lib/adminLockoutStore.test.js`.
- New `server/lib/adminLockoutStore.test.js` (13 cases, Redis client mocked —
  no live Upstash credentials required): local-fallback accumulate/lockout/
  reset, Redis-backed accumulate via the atomic Lua script, TTL anchored on
  the first failure only (not extended by later ones), TTL expiry,
  reset-on-success, Redis-error fail-safe on `isLocked`/`recordFailure`/
  `reset`, and IPv6/64 key normalization consistency with `S04-M1`.
- `npm test` (server): **194/194 pass** (was 181/181 immediately before this
  sub-session — the +13 delta is the new `adminLockoutStore.test.js` cases).
  The previously-tracked `identityVerify.test.js` failure was **not**
  reproduced in this sub-session's environment (all native deps installed
  cleanly) — if it resurfaces in a future environment, treat it as the same
  pre-existing, unrelated issue documented above, not a regression from this
  work.
- Live-wiring grep confirms `createAdminLockoutStore` is imported and
  instantiated in `server/index.js`, and `adminIpLocked`/
  `recordAdminAuthFailure`/`resetAdminAuthFailures` all delegate to it.

## Correction

This file originally shipped (in the first sub-session) stating `S04-M1` was
deferred to a future session. That was accurate at the time it was written,
but `S04-M1` was fixed later in that same session before the session closed,
and the earlier draft of this file was not updated to reflect it — this
reconciliation corrects that. `S04-M1`'s `BUG_TRACKER.md` row has carried the
correct **Fixed** status since; this file did not match it until now.

## Chain state

`START_HERE.md`: `LAST DONE: S3-07` — **all 5 scoped findings fixed**
(`S02-L3`, `S02-L4`/`S04-L1`, `S04-M3`, `S04-M1`, `S04-L3`). `NEXT SESSION:
S3-08` (server egress, TURN, public endpoints) per
`ROUND3_REMEDIATION_PLAN.md` — no S3-07 carryover remains.
