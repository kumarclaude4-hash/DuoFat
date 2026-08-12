# SESSION-S3-13 — Admin surface, part 1 (lifecycle & PII)

**Lane:** SRV
**Status:** Partial — 1 of 3 plan-scoped findings partially advanced this
session (`S05-M1`, one of its two remaining fix items closed). `S05-H2` and
`S05-M3` are untouched — `S05-M3` was already `Partial` before this session
(not re-touched); `S05-H2` remains `Open`.
**Out-of-order note:** this session ran before `S3-10`, `S3-11`, `S3-12` in
the plan's numeric sequence. It was picked up directly because the prior
session (`S3-09`) had already partially advanced `S05-M1` as a side effect
and left it explicitly flagged as carried-forward scope. `S3-10` (duress
eligibility & rules coverage) remains the correct **next** session per
`ROUND3_REMEDIATION_PLAN.md` and `START_HERE.md`'s chain state — this
session does not supersede it.

## Scope (per `ROUND3_REMEDIATION_PLAN.md`)

S3-13 is scoped to 3 findings: `S05-H2` (waitlist deny/expire/revoke path),
`S05-M1` (stop persisting raw operator IPs + uids forever / to stdout),
`S05-M3` (admin session absolute lifetime + binding + bulk revoke;
authenticated refresh).

## Reconciliation before touching anything

Per `BUG_TRACKER.md` at session start:
- `S05-H2 | Open | Verified` — only `GET /admin/api/waitlist` and
  `POST /admin/api/waitlist/approve` exist; no deny/expire/revoke route.
  Not touched this session.
- `S05-M3 | Partial | Verified` — admin sessions already carry a 30-minute
  absolute TTL with expiry sweep; no bulk-revoke path and no IP/UA binding
  yet. Not touched this session.
- `S05-M1 | Partial | Verified` — the `/admin/api/duress/enroll`/`/revoke`
  raw-uid `console.log`s were already fixed in `S3-09`. Still open per the
  tracker: `adminIp: getClientIp(req)` written raw into the permanent,
  no-TTL `adminAuditLog` collection, and 2 `requestId` console lines.

This session picked up exactly the one remaining, well-scoped item that fit
a single bug's worth of change: the raw-`adminIp`-to-Firestore write.

## What was found and fixed

Read the finding's full text (`audit/SESSION-05-ADMIN.md:358-407`) before
changing anything. Two things worth flagging:

1. **The tracker undercounted call sites.** It described "4 call sites"
   for the raw `adminIp` write. Reading `server/index.js` showed all
   `adminAuditLog` writes funnel through a single sink function,
   `auditAdminEvent(action, req, extra)` (`:810`) — there is exactly **one**
   write site, called from **7** places (`grep -c 'auditAdminEvent("'
   server/index.js` → `7`). Fixing the sink fixes every call site at once;
   the "4" in the prior tracker text conflated call sites with something
   else and has been corrected in place.

2. **The finding's own resolution is pseudonymize, not delete.** S05-M1's
   text explicitly notes tension with `S05-H3` (which wants *more* durable
   admin audit, not less) and resolves it as "pseudonymised-but-durable,
   not identifying." That means the correct fix is `ipTag()`, matching the
   pattern already used for every other IP-touching log path in this
   codebase (`/mintToken`, `/linkPreview`, the rate limiters) — **not** a
   Firestore TTL policy on `adminAuditLog`, which would trade away the
   durability `S05-H3` requires. This was a deliberate scope decision, not
   an oversight: a TTL was considered and rejected as contradicting the
   finding's own stated resolution.

**Fix:** `auditAdminEvent()` now writes `adminIp: ipTag(getClientIp(req))`
instead of the raw IP, with an in-code comment documenting the caveat that
`LOG_PEPPER` is per-process/non-persisted (same tradeoff already accepted
for every other `ipTag()` use in this file) — sufficient for "same operator
within one incident window," not for "same operator six months apart,"
which matches what the finding asked for.

## `S06-M3`'s "4 call sites" cross-reference — not re-opened

`S06-M3`'s tracker line (from `S3-09`) references
`index.js:4112`/`:4159 prior to this session` for the two duress
enroll/revoke log lines it fixed — those are `console.log` lines, a
different call path from `auditAdminEvent()`'s Firestore write, and were
already corrected in `S3-09`. Not re-touched or re-described here.

## Files changed

- `server/index.js` — `auditAdminEvent()`'s Firestore write changed from
  `adminIp: getClientIp(req)` to `adminIp: ipTag(getClientIp(req))`, with
  an explanatory comment. Two pre-existing, unrelated mangled
  box-drawing-character bytes in nearby comments (introduced by tooling
  in an earlier pass, not this session's edits) cleaned up in the same
  pass since they were adjacent to lines already being touched.
- `server/lib/adminAuditWiring.test.js` — added one regression test:
  "S05-M1: the audit sink pseudonymises the operator IP, never writes it
  raw" — a structural (source-text) test in the same style as the file's
  existing tests, asserting `adminIp: ipTag(getClientIp(req))` appears in
  `auditAdminEvent()`'s body and the raw form does not.
- `BUG_TRACKER.md` — `S05-M1` row updated: the `adminIp` item marked done
  with evidence; the TTL non-fix documented as a deliberate scope decision
  citing the finding's own tension note; `requestId` lines and the
  overall `Partial` status left in place, not force-closed.

## Test evidence (run this session)

- `node --check server/index.js` — clean.
- `node --test server/lib/adminAuditWiring.test.js` — **6/6 pass**,
  including the new S05-M1 test.
- `cd server && npm test` — **195 pass / 1 fail / 196 total**. The 1
  failure is `lib/identityVerify.test.js`, failing with `Cannot find module
  '@signalapp/libsignal-client'` — the same pre-existing, environment-only
  missing-native-dependency failure documented across `SESSION-S3-05.md`
  through `SESSION-S3-09.md`; re-confirmed by isolating the failing suite
  and reading its `MODULE_NOT_FOUND` output directly this session, not
  assumed from memory.
- Live-wiring check: `grep -n 'adminIp:\s*getClientIp(req)' server/index.js`
  returns no hits; `grep -n 'adminIp:\s*ipTag' server/index.js` confirms the
  new form is present at the sink.

## Verification NOT run (recorded, not fabricated)

- No live Firestore write was made or read back — `adminAuditLog` writes
  are fire-and-forget against a real project, and no Firestore
  emulator/credentials are available in this environment. Correctness was
  established by the structural regression test plus a direct source read
  of the sink function, consistent with `adminAuditWiring.test.js`'s own
  documented limitation ("cannot prove a row reaches Firestore").
- `S05-H2` and `S05-M3` were read only far enough to confirm they were out
  of this session's one-bug scope; neither was investigated in depth.

## Git discipline

**Correction (reconciliation pass, out-of-order-session follow-up):** the
implementation change (`server/index.js`,
`server/lib/adminAuditWiring.test.js`) and this session's `BUG_TRACKER.md`/
`SESSION-S3-13.md` documentation landed together in a single commit,
`e776a1f`, not in separate commits as originally recorded here — the
"committed separately" claim above was inaccurate and is corrected in
place rather than silently left wrong, per protocol (source/`git log` beats
narrative). `START_HERE.md` and `SESSION_INDEX.md` were **not** updated by
that commit; a follow-up documentation-only commit closes that gap without
touching `e776a1f` or any other implementation commit.

## Chain state

`START_HERE.md`'s `NEXT SESSION` line is **not** advanced to `S3-14` by this
session — `S3-13` itself is only partially done (`S05-H2`/`S05-M3` still
open), and the plan's actual next unstarted session is still `S3-10`
(duress eligibility & rules coverage), which this out-of-order session did
not touch. The chain-state block records this session as an additional
`LAST DONE` entry without changing which session is `NEXT`.

---

## Continuation session — finishing S3-13's remaining scope (S05-H2, S05-M1's last item)

**Status:** `S05-M1` now **Fixed** (was `Partial`). `S05-H2` now **Partial**
(was `Open`) — deny path only, per this session's narrower "deny-path"
framing, not the finding's full design fix. `S05-M3` untouched, stays
`Partial` — carried forward, out of this session's 2-finding scope by
deliberate choice (see below).

### Reconciliation before touching anything (source beats tracker, §3)

`BUG_TRACKER.md` at the start of this session claimed `S05-M1`'s only open
item was two clear-text `requestId` console.log lines, on the strength of
"`auditAdminEvent()` — the single sink all 7 call sites funnel through."
That claim was checked against source rather than trusted, per protocol,
and turned out to be **false**: `grep -n 'adminIp:' server/index.js`
returned five hits, not one — `auditAdminEvent()`'s own correct
`ipTag(getClientIp(req))` at its definition, plus **four other call sites**
(`waitlist_approved` at the old `:3939`, `account_unfrozen` at `:4038`,
`duress_enrolled` at `:4153`, `duress_revoked` at `:4201`) each writing
`db.collection("adminAuditLog").add({ adminIp: getClientIp(req), ... })`
**directly**, bypassing the sink entirely. The prior session's "7 call
sites" count conflated `auditAdminEvent(` call sites (6 auth-event ones,
correctly wired) with the total number of places that write to
`adminAuditLog` (11: those 6 plus these 5 direct writes including the
sink's own). This was not caught by `adminAuditWiring.test.js`'s existing
S05-M1 test because that test only inspects `auditAdminEvent()`'s own
function body — it never asserted anything about callers, so a caller that
skipped the sink entirely was invisible to it. Exactly the "wiring, not
existence" trap the same test file's own header comment warns about,
missed one level up from where it was written to catch it.

Worse: `duress_enrolled`/`duress_revoked` also still wrote the **raw** `uid`
into that same direct Firestore call (`uid,` — no `uidTag()`), even though
the adjacent `console.log` two lines above each had already been
pseudonymised in `S3-09`. The finding's own text calls
`action: "duress_enrolled", uid: <raw uid>` in a permanent, no-TTL
collection "the most sensitive" instance of this class of bug — that exact
sentence was still true in source at the start of this session.

### What changed (`server/index.js`)

1. New `reqTag(id)` helper next to `ipTag`/`uidTag` (identical pepper-HMAC
   scheme) — closes the literal remaining `S05-M1` item, the two
   `requestId=${requestId}` console.log lines (`/requestAccess`,
   `/admin/api/waitlist/approve`), now `reqTag(requestId)`.
2. All four direct `db.collection("adminAuditLog").add(...)` call sites
   above now call `auditAdminEvent(action, req, extra)` instead —
   `auditAdminEvent` already accepted an arbitrary `extra` object merged
   into the write, so no signature change was needed, only routing. This
   fixes the raw-`adminIp` gap for all four in one move (`ipTag()` is
   applied inside the sink, not by each caller) and adds `userAgent` to
   rows that previously lacked it, for free, as a side effect of sharing
   the sink.
3. `duress_enrolled`/`duress_revoked` additionally pass
   `uid: uidTag(uid)` instead of the raw `uid` — closing the finding's
   "most sensitive" callout. `account_unfrozen` deliberately **keeps** the
   raw `uid` (there is a pre-existing comment at that call site explaining
   why: it is the operator's Firestore-access-controlled accountability
   record, a different tradeoff than duress enrollment, which the finding
   singles out by name) — not touched, not an oversight.
4. New `POST /admin/api/waitlist/deny` (`S05-H2`), placed directly after
   `/approve`: `requireAdminAuth` gate, same `requestId` format check,
   404 on not-found, 409 if not currently `pending` (mirrors `/approve`'s
   shape exactly), sets `status: "denied"` + `deniedAt`, logs
   `reqTag(requestId)`, audits `waitlist_denied` via `auditAdminEvent()`.
   `GET /waitlistStatus` needed no change — it already echoes whatever
   `status` string the doc holds — and `/mintToken`'s
   `status === "approved"` gate already rejects a denied doc with no
   further change. Admin panel gained a "Deny" button (reusing the
   existing `.action.danger` CSS class already used elsewhere in the
   panel) beside "Approve", disabling both siblings during either request;
   the audit-log table gained a `waitlist_denied` → "🚫 Waitlist denied"
   label.

### Why S05-H2 is Partial, not Fixed

The finding is rated High and its full fix is a design inversion
(operator-issues-invites, or a reviewable request payload, plus expiry on
approved-but-unused invites, revoke-an-approval, pagination/total-count so
a flood is visible instead of silently truncating the operator's
`limit(200)` view, and surfacing `usedByUserId` in the panel). `S3-13`'s own
exit criteria in `ROUND3_REMEDIATION_PLAN.md` names only "deny-path tests,"
not the full redesign, so this session implemented exactly that slice and
is recording it as a slice, not the whole finding — the same discipline
`S3-12`'s S03-M3 session applied to its own deliberately-partial TTL
decision.

### Why S05-M3 was left untouched this session

Two findings were fixed to keep each fully verified rather than three
half-verified (§5's own warning: "a half-verified extra finding is worse
than a fully-verified single finding"). `S05-M3`'s remaining scope
(bulk-revoke across all active admin sessions, plus binding a session to
the IP/UA it was issued from) needs a new admin-sessions list/store shape
this session did not have budget to design and test carefully — deferred
whole, not partially started.

### Verification (lane SRV)

- `node --check index.js` — clean.
- `grep -n 'adminIp:\s*getClientIp' index.js` — zero hits outside comments
  (previously 4).
- `grep -n 'requestId=\${requestId}' index.js` — zero hits (previously 2).
- `npm test` — **197/198 pass**. The 1 failure is the same pre-existing
  `identityVerify.test.js` missing-native-module issue
  (`Cannot find module '@signalapp/libsignal-client'`) documented since
  `S3-05`, reproduced this session, not a regression.
- New tests added to `server/lib/adminAuditWiring.test.js`: "no admin
  action writes adminAuditLog directly, bypassing the pseudonymising sink"
  (asserts exactly 1 total `db.collection("adminAuditLog").add(` call site
  — the sink's own — falsified first by temporarily leaving one direct
  write in place and confirming the test failed with `1 !== 0`, then
  fixed); "duress enrollment/revocation audit rows never carry the raw
  uid"; "waitlist requestId is never logged to stdout in the clear"; and a
  structural `S05-H2` test confirming the deny route exists, is
  admin-gated, rejects non-pending status, sets `status: "denied"`, and
  audits `waitlist_denied` — bounded to that one handler's text slice so a
  match from `/approve` or `/admin/api/locked` cannot pass it spuriously.

### Chain state

`S3-10`/`S3-11`/`S3-12` are already done (per the chain-state block this
session inherited, not re-verified beyond this session's own two
findings). `START_HERE.md`'s `NEXT SESSION` line is updated to `S3-13`'s
one remaining item, `S05-M3` (admin session bulk-revoke + IP/UA binding) —
not advanced to `S3-14`, because `S3-13`'s own exit bar
("suite green with session-lifetime and deny-path tests") is only half
met: the deny-path test landed this session, the session-lifetime
(bulk-revoke/binding) test did not.

---

## Continuation session — E2E-check on S05-H2's deny path (no S3-14/S05-M3 work)

**Status:** No code changes. `S05-M1` was **not** re-touched (already
`Fixed`, per the prior continuation above — not re-opened, not re-verified
beyond a `git log`/`git status` sanity check). `S05-H2` stays `Partial`
(same disposition as before this session — this was a depth-check on the
existing deny-path slice, not new scope), now with source-level evidence
that the deny path is a real, fully-wired mutation path end to end, not
merely something `adminAuditWiring.test.js`'s structural (text) assertions
happen to match.

### What was checked, and how

Starting state confirmed first: `git status` clean, `git log --oneline -8`
showed the prior continuation's work already merged (`fa30e1e` "feat:
implement partial waitlist deny route and admin panel button", merged via
PR #80 at `edc1946`) — nothing pending, nothing to redo.

The chain was then read from the browser-facing end down to the Firestore
write, one hop at a time, rather than trusting that a passing structural
test implies a real path:

1. **UI**: `index.js:1611-1615` — the "Deny" button's `onclick` calls
   `deny(r.requestId, denyBtn, approveBtn)`, a real function
   (`index.js:1647-1664`), not a stub — it disables both buttons, calls
   `api(...)`, and on success calls `loadWaitlist()` + `loadAuditLog()` to
   refresh both panel views from the server.
2. **Client transport**: `api()` (`index.js:1415-1445`) is a shared
   `fetch()` wrapper used by every admin-panel call (approve, deny, unfreeze,
   etc.) — it sets `credentials: "same-origin"` (so the HttpOnly
   `duoshield_admin_session` cookie is sent) and the `x-admin-token` header,
   and treats a `401` as "session expired" (forces logout), not as a
   silently-swallowed error.
3. **Server auth gate**: `requireAdminAuth()` (`index.js:904-938`) is the
   same real gate every other `/admin/api/*` route uses — checks IP
   lockout first (`auditAdminEvent("admin_api_blocked_locked_out", ...)`
   + 429 if locked), then requires either a valid `x-admin-token` (constant-
   time compared via `safeTokenEqual`) or a valid session
   (`hasValidAdminSession(req)`); a failure both audits
   `admin_api_unauthorized` and increments the lockout counter via
   `recordAdminAuthFailure(ip)`. This is not a mock/no-op check — it is the
   identical function gating `/approve`, `/admin/api/locked`, and every
   other admin mutation route.
4. **Mutation**: `index.js:4013-4053` — on a valid `requestId` (format-
   checked against `/^[0-9a-f]{32}$/`) whose doc exists and is currently
   `status: "pending"` (404/409 otherwise, same shape as `/approve`), the
   handler performs a real Firestore write:
   `ref.update({ status: "denied", deniedAt: FieldValue.serverTimestamp() })`
   against `db.collection("waitlist").doc(requestId)` — not a dry-run, not
   an in-memory stub.
5. **Audit trail**: `auditAdminEvent("waitlist_denied", req, { requestId })`
   is called immediately after the mutation succeeds, routing through the
   single pseudonymising sink (`S05-M1`'s fix) rather than writing
   `adminAuditLog` directly.
6. **Read-back paths that make the denial actually take effect**:
   `GET /waitlistStatus` (`index.js:2412-2442`) echoes whatever `status`
   string the doc holds with no special-casing — a denied doc's poll
   response is `{ status: "denied" }`, verbatim from Firestore, not
   filtered or translated. `/mintToken`'s gate
   (`index.js:2286`, `waitlistSnap.data().status !== "approved"`) is a
   strict inequality against the literal string `"approved"`, so
   `"denied"` is rejected by the exact same check that already rejects
   `"pending"` — no separate `denied`-specific carve-out was needed or
   added.
7. **Firestore access control**: `adminAuditWiring.test.js`'s existing
   `adminAuditLog is server-only in firestore.rules` test confirms
   `allow read, write: if false` on that collection, so the audit trail
   this path writes to cannot be read or forged by a client.

No step in this chain short-circuits to a mock, a hardcoded response, or a
no-op — the Deny button drives a real authenticated HTTP request into a
real Firestore mutation with a real durable audit record, confirmed from
source, not merely from `adminAuditWiring.test.js`'s text-matching
assertions (which check the same shape but do not execute it, per that
file's own documented limitation).

### Verification NOT run (recorded, not fabricated)

Same limitation as the prior continuation: no Firestore emulator or
service-account credentials are available in this environment, so no
live HTTP round-trip or live Firestore read-back was performed. This
session's contribution over the prior one is a full manual trace of every
hop in the chain from source, confirming none of them is a stub — it does
not newly execute the path.

### Test evidence (run this session)

- `git status` — clean at session start and end (no code changes made).
- `node --check index.js` — clean.
- `node --test server/lib/adminAuditWiring.test.js` — **10/10 pass**,
  unchanged.
- `cd server && npm test` — **197/198 pass**. The 1 failure is the same
  pre-existing `identityVerify.test.js` missing-native-module issue
  (`Cannot find module '@signalapp/libsignal-client'`), reproduced again,
  not a regression.

### Why no regression test was added

The existing `S05-H2` structural test in `adminAuditWiring.test.js`
already asserts the four properties that matter for a text-level wiring
check (route exists, admin-gated, rejects non-pending, sets `status:
"denied"`, audits `waitlist_denied`) and does so with a bounded slice so a
match from `/approve` or `/admin/api/locked` cannot pass it spuriously —
this session found no gap in that coverage to add a test for. A true
behavioural (live HTTP + live Firestore) test remains `BLOCKED` in this
environment, same as it was for `/approve` before this session and for
every other admin-panel mutation route — not a new limitation introduced
or discovered here.

### Documentation reconciled this session

- `BUG_TRACKER.md`'s `S05-H2` row — verification column and detail cell
  extended with the source-line evidence above; disposition (`Partial`)
  unchanged.
- `SESSION-S3-13.md` (this file) — this section.
- `START_HERE.md` / `SESSION_INDEX.md` — updated to record this E2E-check
  pass without changing `NEXT SESSION` (still `S3-13`'s one remaining item,
  `S05-M3`) or re-describing `S05-M1`/the deny-path implementation itself,
  which were already accurately documented by the prior continuation.

### Chain state

Unchanged by this session: `NEXT SESSION` stays `S3-13`'s remaining scope,
`S05-M3` (admin session bulk-revoke + IP/UA binding; authenticated
refresh) — not started here, per instruction for this session.

---

## S05-M3 — admin session absolute lifetime, IP/UA binding, bulk revoke

**Status: Fixed.** This closes `S3-13`'s last remaining item; the chain can
now move to `S3-14`.

### What was traced before editing (every creation/validation/refresh/
### logout/revocation path)

- **Creation:** `POST /admin/login`'s success branch called
  `createAdminSession()` (no args) and set it as a cookie via
  `adminSessionCookie(sessionId, req, Math.floor(ADMIN_SESSION_TTL_MS / 1000))`.
- **Storage:** a bare `Map<sessionId, expiresAt>` (`adminSessions`), swept by
  a 5-minute `setInterval` that deleted anything past its `expiresAt`.
- **Validation/refresh:** `hasValidAdminSession(req)` read the cookie,
  checked `expiresAt > Date.now()`, and — on every single call, success or
  not distinguished — **unconditionally reset** `expiresAt` to
  `Date.now() + ADMIN_SESSION_TTL_MS` (30 min). This function had exactly
  two call sites: `requireAdminAuth()` (every `/admin/api/*` route) and,
  critically, the unauthenticated `GET /admin` render check that decides
  whether to serve the login gate or the app shell — meaning a session was
  refreshed by page loads alone, not just by admin actions.
- **Logout:** `POST /admin/logout` did `adminSessions.delete(sessionId)` and
  cleared the cookie.
- **Bulk/rotation:** nothing referenced `adminSessions` on token rotation —
  `ADMIN_TOKEN` is read once from `process.env` at module load, so rotating
  it in the environment does not and never did touch already-minted
  sessions. There was no admin-facing bulk-revoke endpoint or button at all.

**Conclusion from the trace, before writing any fix:** the pre-existing
`BUG_TRACKER.md` disposition ("Partial — 30-min absolute TTL now enforced")
was itself wrong. `ADMIN_SESSION_TTL_MS` was never an absolute ceiling — it
was the *idle* window, and because `GET /admin` alone (not even an
authenticated action) reset it, a session note-idling-open tab could
persist indefinitely. This is corrected in the `BUG_TRACKER.md` row itself
(same "correction to the prior disposition" pattern `S05-M1` used), not
silently overwritten.

### The fix

New `server/lib/adminSessionStore.js` — pure, dependency-free, no Firestore
(admin sessions are a short-lived operational concern, same rationale
`adminLockoutStore.js` already documents for why it doesn't persist either;
this module explicitly documents that it is a knowing continuation of that
existing tradeoff, not a new one). Structured like `adminLockoutStore`'s
factory-with-injectable-clock, for the same reason: deterministic time
control in tests without `setTimeout`-based sleeps.

- `create({ ip, userAgent })` → session id, and stores `createdAt`,
  `expiresAt` (idle-timeout-relative), and a separate `absoluteExpiresAt`
  fixed at creation time and never modified afterward.
- `validate(sessionId, { ip, userAgent }, { refresh = true })`:
  1. Missing/unknown id → `{ valid: false, reason: "missing" }` /
     `"not_found"`.
  2. Revoked → `"revoked"`.
  3. Past `absoluteExpiresAt` → `"absolute_expired"` (checked *before* the
     idle check, so an absolute-expired session is never reported as merely
     idle-expired).
  4. Past `expiresAt` → `"idle_expired"`.
  5. `ip` or `userAgent` mismatch → `"ip_mismatch"` / `"ua_mismatch"` —
     **the session record is left intact**, not deleted, on a binding
     mismatch. This was a deliberate choice: an IP mismatch is frequently a
     legitimate carrier/NAT/VPN IP change mid-session for the actual
     account holder, not necessarily an attacker — deleting the session on
     first mismatch would force a full re-login for that ordinary case,
     while just rejecting each mismatched request until the tag matches
     again preserves continuity if the same client's tag returns (e.g. a
     flaky mobile network), and still fully blocks a cookie replayed from a
     genuinely different context throughout.
  6. Otherwise valid; if `refresh` (default `true`), extends `expiresAt` to
     `Math.min(now + idleTtlMs, absoluteExpiresAt)` — the `Math.min` is the
     actual fix for the missing ceiling; a session already near its
     absolute limit gets a shorter effective extension, never a longer one.
- `revoke(sessionId)` / `revokeAll()` (returns the count revoked) /
  `sweep()` (drops anything idle- or absolute-expired, called from the
  existing 5-minute interval, now pointed at the store instead of the raw
  Map).
- `_get(sessionId)` — a diagnostic-only accessor used by the regression
  tests to assert the capped `expiresAt` value directly, rather than only
  being able to observe the eventual pass/fail of a subsequent `validate()`
  call.

**Wiring in `server/index.js`:**
- `ADMIN_SESSION_IDLE_TTL_MS` (30 min, same value as before) and new
  `ADMIN_SESSION_ABSOLUTE_TTL_MS` (8h) are both explicit constants;
  `ADMIN_SESSION_TTL_MS` is kept as an alias to `ADMIN_SESSION_IDLE_TTL_MS`
  so the cookie `Max-Age` sent to the browser (a hint only — the server-side
  store is the actual control) is unchanged.
- `createAdminSession(req)` now takes the request and passes
  `ip: ipTag(getClientIp(req))` (the same pseudonymised tag `S05-M1`
  already writes to the audit log — never the raw IP) and
  `userAgent: req.headers["user-agent"] || ""`.
- New `evaluateAdminSession(req, opts)` wraps `adminSessionStore.validate()`
  with the current request's cookie/ip/userAgent; `hasValidAdminSession(req,
  opts)` is now a thin boolean wrapper over it, preserving its existing
  call signature/behavior for `requireAdminAuth`'s default (refreshing)
  call.
- `requireAdminAuth()` now calls `evaluateAdminSession(req)` (only when no
  valid token was supplied — token auth still never touches the session
  store at all) and includes the resulting `sessionCheck.reason` in both
  the `console.warn` line and the `admin_api_unauthorized` audit event's
  `sessionInvalidReason` field — an `ip_mismatch`/`ua_mismatch` (a plausible
  stolen-cookie replay) is now distinguishable in the durable audit trail
  from an ordinary `idle_expired`/`absolute_expired`/`missing`.
- `GET /admin`'s render check now calls `hasValidAdminSession(req, {
  refresh: false })` — this was the specific fix for "page loads alone
  extend the session": that route needs to know if the caller is
  authenticated (to decide which HTML to serve) but must not have the
  side effect of extending anything, since it requires no auth of its own.
- `POST /admin/logout` calls `adminSessionStore.revoke(sessionId)` (was
  `adminSessions.delete(sessionId)` against the old raw Map — same
  behavior, just moved onto the new store).
- New `POST /admin/api/sessions/revoke-all`: gated by `requireAdminAuth`
  like every other `/admin/api/*` route (so it accepts either the admin
  token or an existing valid session), calls
  `adminSessionStore.revokeAll()`, audits `admin_sessions_revoked_all` with
  the revoked count, and — deliberately — clears the *caller's own* cookie
  in the response too, so a "sign out everywhere" action does not quietly
  spare the person who pressed the button. The admin panel gained a "Sign
  out everywhere" button (with a `confirm()` prompt, since it is
  destructive to every other logged-in operator's session too) next to the
  existing single-session "Sign out".

### Regression tests added

`server/lib/adminSessionStore.test.js` (15 tests, run directly against the
pure store with an injectable clock, same pattern as
`adminLockoutStore.test.js`):

1. `create()`/`validate()` round-trip for a normal session, matching
   context — valid.
2. Refresh before idle expiry extends `expiresAt` (asserted via `_get()`).
3. Two absolute-lifetime tests: (3a) a session that receives no refreshes
   still expires at exactly its absolute ceiling, not later; (3b) a session
   refreshed *inside* its idle window but close to the absolute ceiling has
   its `expiresAt` capped at `absoluteExpiresAt` (not `now + idleTtlMs`),
   asserted directly via `_get()` before also confirming the eventual
   `absolute_expired` rejection past that point.
4. IP-tag mismatch is rejected as `ip_mismatch`, and the session is
   confirmed still present/usable afterward from the correct context
   (non-destructive).
4b. User-Agent mismatch is rejected as `ua_mismatch`, same
   non-destructive confirmation.
5. A revoked session is rejected as `revoked` and cannot be revived.
6. `revokeAll()` invalidates every active session at once and reports the
   correct count; 6b covers the empty-store no-op case reporting `0`.
7. A session refreshed multiple times in a row from the *same* consistent
   context (the ordinary "operator keeps working" case) stays valid across
   all of them and is never spuriously rejected.

Plus edge cases: idle expiry without a refresh, `sweep()` removing both
idle- and absolute-expired entries, and an unknown/missing session id
returning `not_found`/`missing` rather than throwing.

`server/lib/adminAuditWiring.test.js` (+4 tests, source-text wiring checks —
same "wiring, not re-testing the logic" split the file's existing `S05-H2`
test documents): session creation binds `ip`/`userAgent`; `GET /admin`
passes `{ refresh: false }`; the revoke-all route is admin-gated, calls
`adminSessionStore.revokeAll()`, and audits; logout calls
`adminSessionStore.revoke(sessionId)`. `admin_sessions_revoked_all` was
also added to the file's pre-existing required-audit-actions list (the
`S05-M1` wiring test that scans every `auditAdminEvent(...)` call site).

### Test evidence

- `node --check index.js` — clean.
- `node --test server/lib/adminSessionStore.test.js` — **15/15 pass**.
- `node --test server/lib/adminAuditWiring.test.js` — **14/14 pass** (10
  pre-existing + 4 new).
- `cd server && npm test` (full suite) — **216/217 pass**. The 1 failure is
  the same pre-existing `identityVerify.test.js` missing-native-module issue
  (`Cannot find module '@signalapp/libsignal-client'`), reproduced again,
  confirmed not a regression (198 baseline + 15 `adminSessionStore.test.js`
  + 4 `adminAuditWiring.test.js` = 217 total, 216 passing).

### Residual limitations (documented, not hidden)

- The session store is in-memory (a `Map`, same as before this fix) — a
  process restart or a multi-instance deployment without sticky sessions
  still invalidates/fragments sessions. This is a pre-existing
  characteristic of the whole admin-auth subsystem (the lockout store has
  the identical tradeoff, explicitly documented in its own module comment)
  and is out of this finding's scope to change.
- `ADMIN_SESSION_ABSOLUTE_TTL_MS` (8h) is a code constant, not yet wired to
  an env var — unlike `ADMIN_SESSION_IDLE_TTL_MS`/`ADMIN_TOKEN`, there was
  no existing env-configuration convention for this specific value to
  follow, and no requirement was given to make it operator-tunable. Noting
  this as a real limitation rather than silently deciding it doesn't
  matter.
- IP/UA-mismatch handling rejects the mismatched request but does not
  itself trigger a lockout-style escalation (e.g. auto-revoke after N
  consecutive mismatches) — each mismatched request is independently
  audited (`admin_api_unauthorized` with `sessionInvalidReason:
  "ip_mismatch"`/`"ua_mismatch"`) and left for an operator to notice and
  act on (e.g. via "Sign out everywhere"), rather than the system
  automatically revoking on the admin's behalf. This matches the finding's
  literal ask ("bind sessions... according to the audit requirements") —
  binding + audit visibility — without inventing an auto-revoke policy that
  was not requested and could itself become a denial-of-service vector
  (an attacker deliberately triggering mismatches to force-revoke a
  legitimate admin's session).

### Documentation reconciled this session

- `BUG_TRACKER.md`'s `S05-M3` row — moved from `Partial (corrected)` to
  `Fixed`, with the prior disposition's inaccuracy corrected in place (the
  "30-min absolute TTL now enforced" claim was false — it was idle-only)
  and the full fix/evidence documented.
- `SESSION-S3-13.md` (this file) — this section.
- `START_HERE.md` / `SESSION_INDEX.md` — `NEXT SESSION` advanced to
  `S3-14`, since this closes `S3-13`'s last remaining item.

### Chain state

`S3-13` is now fully closed (`S05-M1` fixed, `S05-H2` partial-by-design and
E2E-verified, `S05-M3` fixed). `NEXT SESSION` advances to `S3-14`.
