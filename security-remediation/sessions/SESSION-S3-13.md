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
