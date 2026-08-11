# SESSION-S3-07 — Server limits, memory growth, IP keying

**Lane:** SRV
**Status:** Partial — 3 of 5 scoped findings fixed; 2 deferred to the next SRV session for budget reasons
**Commits:** `000ed14` (implementation), documentation commit follows this file

## Scope (per `ROUND3_REMEDIATION_PLAN.md`)

S3-07 is scoped to 5 findings: `S02-L3` (`mintCooldown` purge), `S02-L4`/`S04-L1`
(byte-count body cap), `S04-L3` (limiter purge / durable store), `S04-M1`
(IPv6 /64 keying for all IP-keyed limits incl. admin lockout), `S04-M3` (XFF
trust configurable).

**This session fixed 3 of the 5** (`S02-L3`, `S02-L4`/`S04-L1` combined = one
fix, `S04-M3`) under an explicit budget constraint from the user ("fix 3 more
bugs"). `S04-M1` (IPv6 /64 keying) and `S04-L3` (limiter purge / durable
store) are deferred, **not** claimed fixed, and remain queued as the next
priority for whichever session picks up SRV work next — either a continuation
of S3-07 or folded into S3-08.

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

## What was deferred (not fixed this session)

- **`S04-M1`** (IPv6 /64 keying for all IP-keyed limits, including admin
  lockout) — this is the largest item in scope: it requires touching every
  IP-keyed Map (`ipHits`, `waitlistIpHits`, the admin lockout counter) to
  collapse an IPv6 address to its /64 prefix before using it as a key, since
  a single residential IPv6 allocation can rotate through the full /64 to
  defeat per-address limiting. Deferred whole, not partially started.
- **`S04-L3`** (limiter purge / durable store — "accepted-to-ops") — the
  `S02-L3` fix above closes the specific `mintCooldown` gap in this finding's
  cluster, but `S04-L3`'s broader ask (moving limiter state to a durable,
  cross-process store rather than per-process `Map`s) is unaddressed and
  remains `Open`.

Both are left `Open` in `BUG_TRACKER.md`, not `Partial` — no code was written
against either this session, so there is nothing to half-credit.

## Test evidence

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

## Chain state

`START_HERE.md`: `LAST DONE: S3-07` (partial — see above), `NEXT SESSION:
S3-07 continuation or S3-08` — the two deferred findings (`S04-M1`, `S04-L3`)
should be picked up before or alongside `S3-08`'s own scope (server egress,
TURN, public endpoints), at the next session's discretion.
