# SESSION-S3-14 — Admin surface, part 2 (input & headers)

**Lane:** SRV
**Status:** COMPLETE — all 6 plan-scoped findings `Fixed`/`Verified`.
**Model:** Opus 5

## Scope (per `ROUND3_REMEDIATION_PLAN.md`)

S3-14 bundles six admin-surface findings, all lane SRV:

- **S05-L1** — `validAdminUid()` on `/admin/api/account/lookup`.
- **S05-L2** — `collectBody()` runs only after `requireAdminAuth()`.
- **S05-L3** — `Cache-Control: no-store` on admin responses.
- **S05-L4** — approve/unfreeze TOCTOU + bounded `.get()`.
- **S05-I2** — remove stale admin comments.
- **S05-I3** — cookie `Secure` server-set + close `safeTokenEqual` length oracle.

Plan exit criterion: **suite green.**

## Reconciliation before touching anything (source beats tracker, §3)

This session resumed an interrupted S3-14 run. The interruption protocol (§7)
applies: read the recorded state, inspect the actual commit, determine what
survived, reproduce only the missing verification, continue from the last safe
checkpoint — **never redo committed work without evidence it is wrong.**

State found at start (`git status` clean; last commit `9edceec`, the merge of
PR #82 "security-audit-remediation"):

- **S05-L1, L2, L3, L4** — already implemented in `server/index.js` and already
  recorded `Fixed (S3-14) / Verified` in `../BUG_TRACKER.md` with source
  line-number evidence, and already covered by structural tests in
  `server/lib/s3_14_admin_remediation.test.js` (8 tests) plus pure-function
  tests in `server/lib/pure.test.js`. Re-verified from current source this
  session (see below); **not re-touched.**
- **S05-I2** — the two comments the finding named (`collectBody`/
  `requireAdminAuthThenBody` doc block, `ADMIN_PAGE_HTML` header) were already
  corrected in source, but `../BUG_TRACKER.md` still carried the row as
  `Open / Carried`.
- **S05-I3** — cookie-Secure trust boundary (`TRUSTED_PROXY_HOPS`/
  `FORCE_SECURE_COOKIES`), `safeTokenEqual` length-oracle removal, and the new
  `Sec-Fetch-Site` cross-site reject were already implemented in source, but
  the row still carried `Open / Carried`, and the **regression test for the
  new Sec-Fetch-Site check had not yet been added** — this was the exact point
  the previous run stopped.

So the residual work was narrow: finish the Sec-Fetch-Site regression test,
fix one stale comment the earlier pass missed, verify all six findings against
current source, and complete the documentation (tracker rows for I2/I3,
this session file, START_HERE, SESSION_INDEX).

## What was found and fixed this session

### The one stale comment the earlier S05-I2 pass missed

While verifying S05-I3, the `requireAdminAuth()` CSRF note (`server/index.js`,
was `:955`) was found to still claim:

> `SameSite=Strict` on the session cookie already blocks the cross-site case…

That is **false against current source**: the cookie is issued `SameSite=Lax`
(`adminSessionCookie()`, `:901`), deliberately — the function's own comment
explains that some Android in-app webviews drop a `Strict` cookie on the
post-login top-level navigation, which silently loses the session. A comment
asserting `Strict` misdescribes the exact CSRF mechanism in force and directly
undercuts the S05-I3 rationale a reviewer reads to trust the fix. This is the
same class of defect S05-I2 removed for the other two comments, so it was
corrected as part of closing S05-I2:

**Fix (`server/index.js`, `requireAdminAuth` CSRF note):** the comment now
states `SameSite=Lax` and explains that Lax already withholds the cookie on
cross-site **sub-requests** (fetch/XHR/form POST — i.e. every mutating
admin/api call), which is the cross-site protection actually relied upon, with
`Sec-Fetch-Site` layered on top as the additive, browser-set, attacker-
unforgeable second signal.

### The finishing regression test (S05-I3 Sec-Fetch-Site)

Added to `server/lib/s3_14_admin_remediation.test.js` (source-level structural
tests, matching the file's stated approach — a live-Firestore behavioural test
remains BLOCKED here, no emulator / no service-account creds):

- **"S05-I3: requireAdminAuth rejects Sec-Fetch-Site: cross-site with a 403
  before any auth work"** — asserts `requireAdminAuth` reads
  `req.headers["sec-fetch-site"]`, keys on the exact value `"cross-site"` only
  (so `same-origin`/`same-site`/`none`/absent stay untouched — purely
  additive), rejects with `403` + `auditAdminEvent("admin_api_blocked_cross_site")`,
  and — crucially — that the cross-site branch appears **before**
  `safeTokenEqual(supplied, ADMIN_TOKEN)` in the function body, so a forged
  cross-site request never reaches the credential-comparison path.
- **"S05-I3/S05-I2: the session cookie is SameSite=Lax and the CSRF comment
  does not misstate it as Strict"** — asserts `adminSessionCookie()` issues
  `SameSite=Lax` AND that `requireAdminAuth`'s body contains no
  `SameSite=Strict` claim. This guards the comment correction above and pins
  the S05-I2/S05-I3 story to reality.

## Source verification of all six findings (this session, current source)

Every claim below was re-checked against `server/index.js` / `server/lib/pure.js`
at commit `3ef65c2`, not carried from the tracker.

- **S05-L1** — `/admin/api/account/lookup` (route `:4435`) calls
  `validAdminUid(uid)` (`:4449`); `grep -n "validAdminUid(uid)"` shows all 4
  uid-taking admin routes use it (`:4288`, `:4449`, `:4486`, `:4543`).
  `validAdminUid` in `pure.js` rejects `.`/`..`/`__…__` in addition to
  slash/backslash/control chars. **Fixed.**
- **S05-L2** — `requireAdminAuthThenBody()` (`:1338`) calls `requireAdminAuth`
  then `collectBody`, `req.destroy()` on failure; `grep -c
  "requireAdminAuthThenBody(req, res"` = 7 (1 definition + 6 POST routes).
  `server.headersTimeout = 15_000` / `server.requestTimeout = 30_000`
  (`:4613-4614`). **Fixed.**
- **S05-L3** — `setBaselineSecurityHeaders` gates on
  `String(req.url||"").startsWith("/admin")` and sets `Cache-Control: no-store`
  + `Vary: Cookie` (`:2176-2178`). **Fixed.**
- **S05-L4** — approve (`:4151`) and unfreeze (`:4324`) wrap read-check-write in
  `db.runTransaction`; unfreeze re-checks `snap.data().locked !== true` inside
  the txn; `GET /admin/api/locked` (`:4257`) and `GET /admin/api/duress/enrolled`
  (`:4412`) both `.limit(500)`. Pagination/total-count deliberately still out of
  scope (documented degradation — the `limit()` half closes the unbounded-
  materialization risk). **Fixed.**
- **S05-I2** — `collectBody`/`requireAdminAuthThenBody` doc block (`:1325`) and
  `ADMIN_PAGE_HTML` header (`:1351`) accurate; the third stale `SameSite=Strict`
  comment corrected this session; `grep -n "SameSite=Strict" index.js` — zero
  hits. **Fixed.**
- **S05-I3** — `adminSessionCookie()` (`:881`) derives `isHttps` from
  `FORCE_SECURE_COOKIES` (`:874`) else `(TRUSTED_PROXY_HOPS > 0 &&
  forwardedProto === "https") || req.socket.encrypted` — `x-forwarded-proto`
  trusted only when the operator declared a trusted proxy present.
  `safeTokenEqual` (`pure.js`) has no length-dependent short-circuit
  (re-confirmed; unit-tested in `pure.test.js`). `Sec-Fetch-Site` cross-site
  `403`+audit reject in `requireAdminAuth` (`:967-973`), before the credential
  path. **Fixed.**

## Files changed this session

- `server/index.js` — 1 comment correction (`requireAdminAuth` CSRF note:
  `SameSite=Strict` → accurate `SameSite=Lax` description). No logic change.
- `server/lib/s3_14_admin_remediation.test.js` — +2 tests (Sec-Fetch-Site
  reject; SameSite=Lax comment accuracy), 8 → 10 assertions in this file's
  visible count, 11 test cases run.
- `BUG_TRACKER.md` — S05-I2 and S05-I3 rows moved `Open / Carried` →
  `Fixed (S3-14) / Verified` with full source + test evidence.
- `security-remediation/START_HERE.md`, `security-remediation/SESSION_INDEX.md`,
  this session file — chain-state + index updates.

## Test evidence (run this session)

```
$ cd server && node --check index.js && node --check lib/pure.js \
    && node --check lib/s3_14_admin_remediation.test.js
index.js OK
pure.js OK
s3_14 test OK

$ node --test lib/s3_14_admin_remediation.test.js
# tests 11  # pass 11  # fail 0

$ npm test          # full server suite
# tests 230  # pass 229  # fail 1
# ✖ lib/identityVerify.test.js  (Error: Cannot find module '@signalapp/libsignal-client')
```

**Baseline before this session's test additions:** 228 tests / 227 pass / 1
fail (same `identityVerify.test.js`). After: 230 / 229 / 1 — the +2 are this
session's new tests; the single failure is unchanged.

## The one failing test — pre-existing and unrelated

`lib/identityVerify.test.js` fails at load with
`Error: Cannot find module '@signalapp/libsignal-client'` (`code:
MODULE_NOT_FOUND`) — a missing optional native module in this environment. It
has failed identically since S3-05 and is independent of the admin/CSRF code
S3-14 touches (this session changed one comment in `index.js` and added tests
in `s3_14_admin_remediation.test.js`; neither imports libsignal). Not a
regression.

## Verification NOT run (recorded, not fabricated)

- **Live-Firestore behavioural test** of the admin routes — BLOCKED (no
  emulator / no service-account creds in this environment). The S3-14 tests are
  source-level structural checks, as the file's own header documents; the
  pure-function halves (S05-L1 `validAdminUid`, S05-I3 `safeTokenEqual`) are
  directly behaviourally tested in `pure.test.js`.
- **RULES / AND lanes** — not in S3-14's scope (all six findings are SRV).

## Chain state

S3-14 is COMPLETE. All six findings satisfy the SRV exit criterion (suite green
except the one pre-existing unrelated failure) and are recorded
`Fixed`/`Verified` in `../BUG_TRACKER.md`. Next scheduled session per
`ROUND3_REMEDIATION_PLAN.md` is **S3-15** (App Check + client provider wiring,
lane AND/RULES — verification BLOCKED here, routed to S3-15b/S3-19b).

## Session record

```
SESSION: S3-14  MODEL: Opus 5  BUDGET: $5 max  CLUSTER: Admin surface part 2 (S05-L1/L2/L3/L4/I2/I3)  STATUS: fixed
CHANGES:
  - server/index.js: correct stale requireAdminAuth CSRF comment (SameSite=Strict -> accurate SameSite=Lax); no logic change (S05-I2/S05-I3)
  - server/lib/s3_14_admin_remediation.test.js: +2 tests — Sec-Fetch-Site cross-site 403+audit reject (before token compare); SameSite=Lax comment accuracy (S05-I3/S05-I2)
  - BUG_TRACKER.md: S05-I2 + S05-I3 rows Open->Fixed (S3-14)/Verified with source+test evidence
  - START_HERE.md / SESSION_INDEX.md / SESSION-S3-14.md: chain-state + index + session log
VERIFICATION:
  PASS: node --check index.js/pure.js/s3_14 test clean; node --test s3_14_admin_remediation.test.js 11/11; npm test 229/230
  FAIL: lib/identityVerify.test.js (pre-existing, unrelated — missing native @signalapp/libsignal-client; failing since S3-05, not a regression)
  BLOCKED: live-Firestore behavioural admin-route test (no emulator / no service-account creds)
  NOT RUN: RULES / AND lanes (not in S3-14 scope — all six findings are SRV)
COMMIT: 3ef65c2 (implementation + tests) ; docs commit recorded in SESSION_INDEX  WORKTREE: clean
NEXT SESSION: S3-15  (App Check + client provider wiring — lane AND/RULES, verify BLOCKED -> S3-15b/S3-19b) — Finding: S10-N1 (Firebase App Check provider wiring in client + rules enforcement scaffold; enforcement enable = runbook; sideloaded-APK caveat = accepted). Source-review + rules/client scaffold only; enable step is operator. Do NOT start S3-15 work under S3-14.
```
