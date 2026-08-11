# SESSION-S3-08 — Server egress, TURN, public endpoints

**Lane:** SRV
**Status:** Partial — 3 of 4 plan-scoped findings closed (1 fully fixed, 1
re-verified already-fixed from source, 1 half-fixed by design); `S04-I1` not
started this session.
**Commits (implementation, prior sub-session — reconciled, not redone):**
- `8f6b206` — `S04-M2` (TURN credential hardening) + `S04-I3` (preview
  provenance) + tracker corrections for `S04-M2`, `S04-L2`, `S06-L2`,
  `S04-I3`.
**Commits (this reconciliation pass):** none to `server/` — no source change
was needed; see "What this session did" below.

## Scope (per `ROUND3_REMEDIATION_PLAN.md`)

S3-08 is scoped to 4 findings: `S04-M2` (TURN cred TTL + aggregate cap +
outbound timeout), `S04-I1` (`/status` + `/` auth / publish counters),
`S04-I3` (preview provenance to client), `S04-L2`/`S06-L2` (`/duress-lock`
authenticated + rate-limited — one code location, two tracker rows).

## What this session did

This was a **reconciliation and closeout** pass, not a fresh implementation
session. Per the task brief, the prior sub-session had already implemented
and committed `S04-M2` and `S04-I3`, and had re-verified `S04-L2`/`S06-L2`
from source, but stopped mid-session immediately after editing the
`S04-I3` tracker row — before running full verification or writing this
log. This session:

1. Ran `git status` / `git diff --stat` / `git log --oneline -12` and
   confirmed the working tree was **clean** — everything claimed by the
   prior sub-session was already committed at `8f6b206`, on top of the
   `S3-07` merge (`00fda8d`). No uncommitted work existed to lose or redo.
2. Re-verified each of the three touched findings **from source**, not from
   the tracker narrative (SESSION_PROTOCOL §3):
   - **`S04-M2`** — confirmed `checkTurnDailyCap()` (`server/index.js:516`),
     `pure.clampTurnCredentialTtlSeconds()` (`server/lib/pure.js:126`), and
     an `AbortController`-based fetch timeout are all live in the
     `/turnCredentials` handler (`server/index.js:2842-2867`), not just
     defined and unused.
   - **`S04-I3`** — confirmed `pure.previewDomainFromUrl()`
     (`server/lib/pure.js:107`) is called from the `/linkPreview` success
     path (`server/index.js:3183`) with the *final* redirect-chain URL, not
     the originally-submitted host.
   - **`S04-L2`/`S06-L2`** — read the full `/duress-lock` handler
     (`server/index.js:3566-3694`) end to end. Confirmed it is genuinely
     fixed, not merely tracker-edited: the endpoint requires a single-use,
     uid-bound nonce (issued only to an authenticated caller by
     `/requestLockNonce`, itself gated by ID-token verification + an
     eligibility check), validates the nonce's shape and expiry, consumes
     it and writes the lock in one Firestore transaction (closing a
     TOCTOU race), and — the specific S06-L2 gap — is gated by
     `checkIpRateLimit(clientIp)` as the handler's first line, 429ing and
     logging on rejection. `checkIpRateLimit()` itself
     (`server/index.js:1003`) is a real fixed-window counter, not a stub.
     Traced every legitimate caller: `AccountLockWorker` (Android,
     fallback path when the synchronous in-app lock write failed offline)
     is the only caller, and the nonce-gated flow does not block it — the
     worker already holds a nonce obtained while signed in. No further
     code change was warranted; the tracker's "Fixed (pre-existing,
     S3-08)" disposition is accurate.
3. Ran full verification (see below) — the prior sub-session had not.
4. Closes out the session's documentation (this file, chain state,
   `SESSION_INDEX.md`) which the prior sub-session had not reached.

## `S04-I1` — explicitly not addressed this session

`S04-I1` (`/status` and `/` unauthenticated, publish counters) remains
`Open | Carried` in `BUG_TRACKER.md`, unchanged. It was in scope per
`ROUND3_REMEDIATION_PLAN.md`'s S3-08 row, but was not part of the four
findings this continuation was scoped to reconcile/finish, and no code
addressing it exists in the current tree (verified: no auth check or
counter gating around the `/status` or `/` handlers in `server/index.js`).
Recorded here rather than silently dropped — a future session should either
fold it into a small standalone fix or schedule it explicitly, since
`START_HERE.md`'s "Round 3 — 103 findings" count still includes it as open.

## Test evidence (run this session)

- `node --check server/index.js` — clean.
- `node --check server/lib/pure.js` — clean.
- `node --test server/lib/pure.test.js` — **58/58 pass**, including the
  `S04-M2` cases (`clampTurnCredentialTtlSeconds`: caps above ceiling, raises
  below floor, passes through in-range, falls back to ceiling on
  missing/non-numeric env) and the `S04-I3` cases (`previewDomainFromUrl`:
  labels with the final redirect hop not the original host — the exact
  phishing scenario the finding describes, strips a leading `www.`, falls
  back to `fallbackHostname` on an unparseable `finalUrl`).
- `cd server && npm test` — **185 pass / 1 fail / 186 total**. The 1 failure
  is `lib/identityVerify.test.js`, which fails with `Cannot find module
  '@signalapp/libsignal-client'` in this sandbox — a missing native
  dependency in the current environment, not a code regression. This is the
  same pre-existing, unrelated failure documented across `SESSION-S3-05.md`,
  `SESSION-S3-06.md`, and `SESSION-S3-07.md`; confirmed again by running it
  in isolation (`node --test lib/identityVerify.test.js`) and reading the
  `MODULE_NOT_FOUND` stack.
- Live-wiring greps (not just definition-exists): `pure.clampTurnCredentialTtlSeconds`,
  `checkTurnDailyCap`, `pure.previewDomainFromUrl`, and `checkIpRateLimit` are
  all called from `server/index.js`, confirmed above with line numbers.

No new tests were added this session — the prior sub-session's tests
(`+3 previewDomainFromUrl` cases, `+4 clampTurnCredentialTtlSeconds` cases,
already counted in the 58/58 above) already cover the fixed behavior, and no
new code was written this session to require new coverage.

## Verification NOT run (recorded, not fabricated)

- No live Cloudflare TURN credential exchange was exercised (would require
  real `TURN_KEY_ID`/`TURN_API_TOKEN` credentials and network egress); the
  timeout/cap/TTL logic is covered at the pure-function level plus a
  live-wiring grep, per SESSION_PROTOCOL's toolchain-blocked guidance.
- No live Firestore transaction was exercised against `/duress-lock`
  (would require a Firestore emulator or live project); correctness was
  established by full source read plus the existing test coverage referenced
  above, not a new integration test.

## Documentation reconciled this pass

- `BUG_TRACKER.md` — no changes; the `S04-M2`, `S04-I3`, `S04-L2`, `S06-L2`
  rows written by the prior sub-session were re-verified against source and
  found accurate, so they stand unedited.
- This file (new).
- `START_HERE.md` chain state — updated: `LAST DONE: S3-08`, `NEXT SESSION:
  S3-09`.
- `SESSION_INDEX.md` — Round 3 row updated to include S3-08.

## Chain state

`START_HERE.md`: `LAST DONE: S3-08` — 3 of 4 plan-scoped findings closed
(`S04-M2` fixed, `S04-L2`/`S06-L2` fixed — re-verified pre-existing, `S04-I3`
partial by design — provenance fixed, failure-indistinguishability
deliberately deferred to a coordinated client change). `S04-I1` remains
`Open`, not attempted this session — carry it forward explicitly rather than
folding it silently into "all remaining open." `NEXT SESSION: S3-09` (duress
server enforcement) per `ROUND3_REMEDIATION_PLAN.md`.
