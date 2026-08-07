# SESSION 05 — Admin Surface (TB-5 / D1–D8)

**Scope:** the operator console and everything that authenticates or authorizes it:

- auth core — `ADMIN_TOKEN` (`server/index.js:537`), `adminIpFails` / `adminIpLocked` /
  `recordAdminAuthFailure` (`:540`, `:558`, `:565`), `adminSessions` /
  `createAdminSession` / `hasValidAdminSession` / `adminSessionCookie`
  (`:542`, `:589`, `:595`, `:581`), `requireAdminAuth` (`:610`)
- `pure.js` helpers the above depend on: `safeTokenEqual` (`:24`), `validAdminUid` (`:34`),
  `getCookie` (`:43`)
- routes — `POST /admin/login` (`:2503`), `POST /admin/logout` (`:2539`), `GET /admin` (`:2551`),
  `GET /admin/api/waitlist` (`:2575`), `POST /admin/api/waitlist/approve` (`:2603`),
  `GET /admin/api/locked` (`:2655`), `POST /admin/api/locked/unfreeze` (`:2681`),
  `GET /admin/api/duress/enrolled` (`:2728`), `GET /admin/api/account/lookup` (`:2756`),
  `POST /admin/api/duress/enroll` (`:2789`), `POST /admin/api/duress/revoke` (`:2838`),
  `GET /admin/api/auditlog` (`:2885`)
- the served page — `ADMIN_PAGE_HTML` (`:805-1361`), `buildAdminCsp` (`:1380`),
  `setBaselineSecurityHeaders` (`:1388`)
- the state the admin surface mutates, and whether anything downstream enforces it:
  `firestore.rules:321` (`duressEligibility`), `:341` (`accountLock`), `:368` (`waitlist`),
  `:386` (`adminAuditLog`); `firestore.indexes.json`; the consuming server paths
  `POST /mintToken`'s waitlist consumption (`:1483-1503`) and
  `POST /requestLockNonce` (`:2362`)
- config/documentation surface: `server/README.md`, `server/package.json`

**Threat model applied:** the attacker is unauthenticated for the admin surface (they hold no
operator secret) but can reach every route, controls a routable IPv6 /64, and automates. They may
also hold approved app accounts. Separately considered: an operator who is compromised, coerced,
or simply mistaken — because this surface is the one that can undo the system's safety latches.
Only server-side enforcement counts as a control.

**Result: 0 Critical / 3 High / 3 Medium / 4 Low / 3 Info.**

The authentication *mechanics* here are good — genuinely better than most hand-rolled admin
panels. Constant-time compare, a nonce-based CSP that replaces `'unsafe-inline'`, a 256-bit
session id, `HttpOnly` + `SameSite=Strict` + `Path=/admin`, and a page that builds every row with
`textContent`. I found no XSS, no CSRF, no auth bypass, and no injection in the panel.

The problems are one level up, in what the surface *is* rather than how it is coded. Its single
factor is an environment variable that nothing validates and no document even mentions
(S05-H1). The largest thing it gates — waitlist approval — is presented to the operator with
literally zero information to decide on, so "manually approved, invite-only" is not a review
process (S05-H2). And the actions it performs are not durably recorded: audit writes are
fire-and-forget *after* the state change commits, and no authentication event is logged at all
(S05-H3). For the one surface in this system that can delete an `accountLock` document — which
`firestore.rules:353` protects with `allow delete: if false` precisely because it must be
irreversible from the client — that combination is the finding.

---

## Verified-correct (positive assurances — carry into Session 10)

Independently derived from the code, not from comments:

- **No XSS in the admin panel.** Every value that originates in Firestore is written with
  `textContent` (`:1031`, `:1035`, `:1082`, `:1133`, `:1177`, `:1251`, `:1256`) or set as a
  `className` from a fixed literal (`:1181`). `innerHTML` is used only as `= ""` to clear
  tbodies (`:1023`, `:1074`, `:1125`, `:1235`) — never with data. The only substitutions into the
  HTML template are `String(authenticated)` (a boolean) and a base64 nonce (`:2565-2566`), and
  each placeholder occurs exactly once, so `String.replace`'s first-match-only semantics are not
  a latent breakage.
- **The CSP is a real nonce CSP, not decorative.** `buildAdminCsp` (`:1380`) emits
  `default-src 'none'; script-src 'nonce-…'` with a fresh 128-bit nonce per response (`:2555`),
  plus `base-uri 'none'`, `form-action 'self'`, `frame-ancestors 'none'`, `connect-src 'self'`.
  `'unsafe-inline'` appears only in `style-src`. Injected `<script>` without the nonce cannot
  run, and `default-src 'none'` already covers `object-src`. The write order is also correct:
  `setBaselineSecurityHeaders` (`:1413`) sets the strict API CSP for everything, and only the two
  HTML routes override it via `writeHead`, which takes precedence.
- **The token never reaches page JavaScript.** `TOKEN` is initialized to `""` (`:954`) and never
  assigned; login is a native form POST (`:870`) and authentication thereafter is the `HttpOnly`
  cookie. `api()` sends `x-admin-token: ""` (`:971`), which `requireAdminAuth` correctly treats as
  absent (`:624` — `supplied &&`). So an XSS in this page could not exfiltrate the operator
  secret, only ride the session. That is the right property, and it is a real improvement over
  the `prompt()`-and-hold-in-memory design the comment at `:801` still describes.
- **Constant-time token comparison is correctly implemented.** `safeTokenEqual`
  (`pure.js:24-29`) length-checks before `crypto.timingSafeEqual` (which throws on unequal
  lengths) and coerces both sides through `Buffer.from(String(...))`, so a non-string
  `x-admin-token` cannot throw.
- **Cookie flags are right.** `HttpOnly`, `SameSite=Strict`, `Path=/admin`, `Max-Age` matching
  the server TTL, `Secure` when the request arrived over TLS (`:584`). `Path=/admin` correctly
  scopes it to `/admin/login`, `/admin/logout` and `/admin/api/*` and nothing else. Logout is
  server-side (`:2539-2548`) — it deletes the in-memory session, not just the cookie — so
  sign-out is a real revocation and does not depend on JavaScript.
- **`getCookie` parses safely** (`pure.js:43-54`): splits on the *first* `=` per pair
  (`indexOf`, not `split`), so a session id containing `=` round-trips, and exact-matches the
  cookie name rather than prefix-matching.
- **Firestore rules deny clients everything this surface manages.** `waitlist` and
  `adminAuditLog` are `read, write: if false` (`firestore.rules:369`, `:387`);
  `duressEligibility` is owner-read / `write: if false` (`:322-323`); `accountLock` is a genuine
  one-way latch — create/update only with `locked == true`, `delete: if false` (`:344-353`). So
  none of the admin state is reachable or forgeable from the app, and the audit log cannot be
  enumerated by an attacker who holds an account.
- **Waitlist consumption is atomic and single-use.** `/mintToken` reads and flips the waitlist
  doc to `used` *inside* the same transaction that claims the identity slot
  (`:1483-1503`), requires `status === "approved"` (`:1495`), and validates the id against
  `^[0-9a-f]{32}$` (`:1490`). One approved request can therefore mint exactly one account. This
  is the correct construction and it holds.
- **Input validation on the mutating routes.** `requestId` is regex-pinned
  (`:2614`); `uid` goes through `validAdminUid` (`:2692`, `:2800`, `:2849`), which bounds length
  and rejects `/`, `\`, and control characters — so no Firestore path traversal on those three.
  Duress enrollment additionally requires `identities/{uid}` to exist (`:2805-2810`), so it
  cannot be granted blind to a non-account. Every route parses JSON in a `try` and returns 400
  rather than 500 on garbage (`:2608`, `:2686`, `:2794`, `:2843`).
- **State-existence is checked before mutation**, giving correct 404/409 instead of silent
  no-op writes: approve rejects non-`pending` with 409 (`:2626-2630`), unfreeze 404s on a missing
  lock (`:2699`), revoke 404s on a missing eligibility record (`:2856`).
- **Error hygiene holds.** Every admin route funnels exceptions through `sendServerError`
  (`:685`), which logs the stack server-side and returns only a random correlation ref. No
  Firestore error text, collection path, or project id is echoed.
- **The required composite index exists.** `waitlist (status ASC, createdAt DESC)` is present in
  `firestore.indexes.json:66-73`, so `GET /admin/api/waitlist` will not fail closed on a missing
  index; the other three admin queries are single-field equality/order and are served
  automatically.
- **The 503-on-unconfigured path fails closed.** With no `ADMIN_TOKEN`, `requireAdminAuth`
  (`:617`) and `/admin/login` (`:2513`) both refuse rather than defaulting to open — the
  `|| ""` at `:537` combined with the `supplied &&` guard at `:624` means an empty supplied token
  can never match an empty configured token.

---

## S05-H1 (High) — The entire admin surface has one authentication factor: an environment variable with no minimum entropy, no startup validation, no rotation, no expiry, no documentation, and no working brute-force ceiling

**Location:** `server/index.js:537` (`const ADMIN_TOKEN = process.env.ADMIN_TOKEN || ""`),
`:610-632` (`requireAdminAuth`), `:558-573` (the only rate control),
`server/README.md` (the omission).

**Trust boundary:** TB-5 — operator ↔ admin API. This token *is* TB-5.

**Root cause:** the token is accepted as-is. There is no length or entropy check, no startup
assertion, no warning log, and — critically — **no documentation anywhere that tells the operator
to generate a random one**. `server/README.md` documents exactly two environment variables
(`GOOGLE_APPLICATION_CREDENTIALS_JSON` and `MAX_INITIAL_MESSAGE_AGE_MS`) and never mentions
`ADMIN_TOKEN`, `TURN_TOKEN_ID`, `TURN_API_TOKEN`, or the B2 keys. An operator following the
README does not know this variable exists; an operator who discovers it from the 503 message
("Admin panel not configured", `:620`) will type something memorable, because nothing tells them
not to.

**Why the ceiling does not hold.** `adminIpLocked` (`:558`, 10 failures / 15 min per IP) is the
*only* control on guessing, and it fails three independent ways:

1. **It keys on the full IP** (`adminIpFails.get(ip)` via `getClientIp`, `:611`). Per
   **S04-M1**, a commodity IPv6 /64 supplies 2⁶⁴ distinct buckets, so the ceiling is 10 guesses
   *per source address*, i.e. no ceiling. Session 04 deferred this finding's severity to this
   session on the condition "if `ADMIN_TOKEN` is not high-entropy, re-rate S04-M1 upward" —
   **nothing enforces or documents high entropy, so S04-M1 should be re-rated High in Session 10.**
2. **There is no global counter.** `requireAdminAuth` has no aggregate failure tally, no
   exponential backoff, and no notion of "this server is under attack." A distributed guesser is
   entirely unmetered.
3. **It resets on restart.** `adminIpFails` is in-memory (S04-L3), so any deploy, crash, or the
   heap exhaustion of **S04-H2** hands the attacker a clean slate — and S04-H2 is triggerable by
   any single app account.

**Nothing else stands behind it.** No second factor, no allowlist of operator source addresses,
no re-authentication before destructive actions, no separation between read and write admin
capabilities, and no way to rotate the token without a redeploy (or to revoke sessions minted
under an old token — see S05-M3). One string is the whole boundary.

**Impact if guessed:**

- `POST /admin/api/locked/unfreeze` (`:2704`) — `ref.delete()` on `accountLock/{uid}`. This is
  the *only* code path in the system that can clear that document; `firestore.rules:353` denies
  deletion to every client specifically so the lock is irreversible from a compromised or
  coerced device. Guessing this token defeats the security-lockout latch for every account.
- `POST /admin/api/waitlist/approve` (`:2631`) — mints account-creation capability at will,
  bypassing the invite gate entirely.
- `POST /admin/api/duress/enroll` / `revoke` (`:2811`, `:2861`) — flips the duress-PIN flag on
  any account.
- `GET /admin/api/locked`, `/duress/enrolled`, `/auditlog` — dumps the full list of locked UIDs,
  the full list of duress-enrolled UIDs, and the operator's IP history. For a tool whose users
  may be under coercion, "which accounts have a duress PIN" is among the most sensitive facts the
  system holds.

**Honest severity discussion.** Rated **High**, not Critical, because exploitability depends on a
value I cannot read: if the operator happened to paste 32 random bytes, unlimited guessing is
still computationally infeasible and every other layer here is sound. I am rating the *absence of
any assurance* — the system is one undocumented human choice away from full admin compromise, and
it neither guides that choice nor bounds the consequence of getting it wrong. That is a design
defect independent of the current token's value.

**Fix:**
1. Validate at startup, not at request time: refuse to boot (or refuse to serve `/admin*`) unless
   `ADMIN_TOKEN` is ≥ 32 bytes of high-entropy material; log loudly when the panel is disabled
   for that reason. Store and compare a hash, not the raw value.
2. Document it in `server/README.md` with a generation command
   (`openssl rand -base64 32`) alongside the other required variables.
3. Add a **global** failure counter with exponential backoff to `requireAdminAuth`, independent
   of source IP, and normalize the per-IP bucket to a /64 (S04-M1).
4. Log every authentication failure with `ipTag` (S05-H3) so brute force is at least visible.
5. Add a second factor (TOTP is ~30 lines and this is a single-operator panel), and require
   re-authentication for `unfreeze` and `duress/enroll` specifically.
6. Support rotation: read the token at request time rather than at module load, and invalidate
   `adminSessions` when it changes.

---

## S05-H2 (High) — The waitlist queue carries no information about the requester, so "invite-only, manually approved" is unreviewable by construction; there is no deny, expire, or revoke path, and a flood permanently hides legitimate requests

**Location:** `server/index.js:1571-1575` (what `/requestAccess` stores), `:2579-2588` (what the
admin API returns), `:1026-1047` (what the operator sees), `:2603` (the only decision available).

```js
// server/index.js:1571-1575 — the entire content of a waitlist request
const requestId = crypto.randomBytes(16).toString("hex");
await db.collection("waitlist").doc(requestId).set({
  status:    "pending",
  createdAt: FieldValue.serverTimestamp(),
});
```

```js
// server/index.js:2587 — and the entire content of the operator's queue
return { requestId, createdAt };
```

**Trust boundary:** TB-5. Account creation is gated on a human decision that TB-5 exposes, but
TB-5 hands the human nothing to decide with.

**Root cause:** `/requestAccess` is unauthenticated and takes **no body** (`:1550`) — no invite
code, no contact, no justification, no reference. So a pending request is a random hex string and
a timestamp. The admin panel renders exactly those two columns plus an "Approve" button
(`:901`, `:1029-1044`). There is no possible criterion for approving one row over another. The
comment at `:1552` calls this "invite-only" and `:1554` says the operator "manually approves" —
but manual approval of indistinguishable tokens is a coin flip, not a control.

**Exploit path (the gate does not need to be broken, only used):**

1. Attacker sends `POST /requestAccess` — no auth, no body, and per **S04-M1** the 5-per-15-min
   per-IP limit is bypassable by rotating within an IPv6 /64. Each call writes a Firestore
   document unconditionally.
2. They generate, say, 500 pending requests over an hour.
3. `GET /admin/api/waitlist` is `orderBy("createdAt","desc").limit(200)` (`:2581-2582`). The
   operator sees the **newest 200** — all attacker-generated. Legitimate requests older than the
   200th newest are **not visible in the panel at all**, and there is no pagination, no cursor,
   and no search. A sustained trickle keeps them permanently invisible: a denial of onboarding
   for real users, plus an unbounded Firestore write bill.
4. Whatever the operator approves, the attacker has an ~n/(n+1) chance of receiving it, and can
   simply poll `GET /waitlistStatus?requestId=…` (unauthenticated, `:1594`) across all 500 ids to
   learn which one was approved.

**Compounding defects in the same lifecycle:**

- **No deny.** The only mutation is `pending → approved` (`:2631`). A junk request can never be
  rejected or deleted, so the queue is monotonically growing garbage forever. `status` values
  the code produces are `pending` / `approved` / `used` — there is no `denied`.
- **Approved invites never expire.** No TTL, no expiry check — `/mintToken` accepts any doc with
  `status === "approved"` regardless of age (`:1495`). An approved-but-unused request is a
  permanent account-creation credential.
- **Nothing binds an invite to its requester.** The requestId is a bearer token created by an
  anonymous HTTP call; it is freely transferable and sellable. Approving "a request" therefore
  approves *whoever ends up holding the string*, not the person who asked.
- **No visibility into outstanding invites.** The panel queries only `status == "pending"`
  (`:2580`), so the operator cannot see how many approved-but-unused invites exist, and there is
  no endpoint to revoke one.
- **Post-hoc linkage exists but is never surfaced.** `/mintToken` writes
  `usedByUserId` onto the waitlist doc (`:1501`), permanently linking an invite to a permanent
  account id — available for forensics, but not exposed to the operator, and see S05-M1 on
  whether it should be stored in the clear at all.

**Honest severity discussion.** Rated **High** because invite-only account creation is a
load-bearing abuse control in this design — it is what keeps the Firestore-backed, largely
unmetered app surface from being open to the world — and it is fully defeated without touching
a single cryptographic or authorization boundary. It is not Critical because it grants only an
ordinary app account (all per-account authorization still applies) and because the flood
component depends on S04-M1.

**Fix:**
- Invert the flow: the operator **issues** invite codes (`POST /admin/api/invite/create`,
  returning a single-use code with a TTL) and `/requestAccess` is removed. Then approval is a
  decision about a person the operator already knows, made out of band, and the queue disappears.
- If the request-then-approve flow must stay: require a payload that carries reviewable content
  (a referral code from an existing account, or a contact the operator can verify), display it in
  the queue, and make `/requestAccess` cost something (proof-of-work or an existing account's
  signature) so a Firestore write is never free.
- Add `deny` and `delete` actions and a `denied` status; add pagination and a total-pending count
  so a flood is visible rather than silently truncating the view.
- Give approvals a TTL (`expiresAt`, checked inside `/mintToken`'s transaction), add a
  `GET /admin/api/invites?status=approved` view, and a revoke endpoint.

---

## S05-H3 (High) — Admin actions are not durably audited and admin authentication is not audited at all, contradicting the "tamper-evident record" the code claims to provide

**Location:** `server/index.js:2634-2640`, `:2707-2713`, `:2817-2822`, `:2864-2869` (the four
fire-and-forget audit writes), `:2880-2884` (the claim), `:565-573` and `:2519-2524` (the
unlogged authentication events).

```js
// server/index.js:2704-2713 — the state change commits first, unconditionally…
await ref.delete();
console.log(`[admin] account unfrozen: uid=${uid}`);

// Audit log — non-fatal; never block the response on this write
db.collection("adminAuditLog").add({ ... })
  .catch((auditErr) => console.warn("[admin] audit log write failed:", auditErr.message));
```

```js
// server/index.js:2882-2884 — the claim
//   "so the operator has a tamper-evident record of who did what and when."
```

**Trust boundary:** TB-5. An audit log is the control that makes the *rest* of TB-5 recoverable:
it is what turns "the admin token may have leaked" from an unanswerable question into an
investigation. Under a threat model that already includes coerced operators and seized
infrastructure, this is not bookkeeping.

**Four distinct gaps:**

1. **The audit write is not part of the action.** `await ref.delete()` (`:2704`) commits, then
   an un-awaited `add()` is fired. If it rejects — permission change, quota, transient
   unavailability, or the process being killed in between — the account is unfrozen with **no
   record**, and the only trace is a `console.warn` in ephemeral Render stdout. The response is
   already `200 {ok:true}`, so the operator has positive confirmation of an unrecorded action.
   The same pattern applies to waitlist approval (`:2635`), duress enroll (`:2817`), and duress
   revoke (`:2864`).
2. **Authentication is never logged.** Neither `recordAdminAuthFailure` (`:565`) nor the
   `/admin/login` failure branch (`:2519-2524`) nor the lockout branch (`:612`) nor a
   *successful* login (`:2525`) writes anything — not to `adminAuditLog`, not even to
   `console`. Compare `/mintToken`, which logs its rate-limit hits with `ipTag` (`:1442`). So a
   brute-force campaign against the highest-privilege surface in the system is **completely
   invisible**: no log line, no counter, no alert. Combined with S05-H1's bypassable ceiling,
   an attacker can guess indefinitely and the operator has no way to know it happened, before
   or after.
3. **Reads are not logged.** Dumping every locked UID and every duress-enrolled UID
   (`:2655`, `:2728`, `:2756`) leaves no trace. If the token leaks, there is no way to determine
   whether the sensitive lists were exfiltrated — only whether anything was written.
4. **It is not tamper-evident.** The entries are ordinary Firestore documents written with the
   Admin SDK. Anyone holding the service-account credential — which is the same server whose
   compromise you would be investigating — can rewrite or delete them. There is no hash chain,
   no append-only enforcement, no off-box sink. "Tamper-evident" requires that modification be
   *detectable*; nothing here makes it so. `GET /admin/api/auditlog` also `limit(100)`s with no
   pagination (`:2891`), so older entries are simply not visible in the panel.

**Honest severity discussion.** Rated **High** rather than Medium because of what the missing
records are about, not because a missing log is itself an exploit. The unaudited operations are
the deletion of the `accountLock` latch and the duress-eligibility flag — the two pieces of
state this system treats as safety-critical — and the wholly unaudited event class is
authentication to the surface that performs them. The false assurance is part of the finding: an
operator reading `:2883` believes they have a tamper-evident record and will make
incident-response decisions on that basis.

**Fix:**
- Write the audit entry **inside the same transaction/batch** as the state change
  (`db.batch()` for unfreeze/approve/enroll/revoke), and fail the request if the audit write
  fails. An action that cannot be recorded must not happen.
- Log every authentication outcome — success, failure, lockout — to `adminAuditLog` and to
  stdout with `ipTag` (`:668`), and alert on failure bursts.
- Log read access to the locked and duress-enrolled lists.
- Make it append-only for real: enforce immutability in `firestore.rules` for the audit
  collection (currently `write: if false` for clients only — the Admin SDK bypasses rules
  entirely), chain each entry to the previous entry's hash, and mirror to an off-box sink the
  server cannot rewrite.
- Paginate `/admin/api/auditlog` and either soften or remove the "tamper-evident" claim at
  `:2883` until it is true.

---

## S05-M1 (Medium) — The admin surface persists raw operator IPs and raw user UIDs in plaintext to Firestore forever, and logs raw UIDs to stdout, directly violating the project's own SEC-L01 log-hygiene control

**Location:** `server/index.js:2638`, `:2711`, `:2820`, `:2867` (`adminIp: getClientIp(req)`),
`:2632`, `:2705`, `:2815`, `:2862` (raw `uid` to stdout); the control being violated is `ipTag`
(`:668`) / `uidTag` (`:674`) and its stated rationale at `:657-666`.

```js
// server/index.js:657-666 — the established policy
// SEC-L01: raw client IPs were written to persistent logs. For a privacy tool
// whose threat model includes log seizure/subpoena, an IP is directly
// identifying …
```

```js
// server/index.js:2711 — the admin routes do exactly that, to a *permanent* store
adminIp: getClientIp(req),
```

Every other IP-touching path in the server was converted to `ipTag` — `/mintToken` (`:1442`),
`/linkPreview`, the rate limiters. The admin routes were not, and they are worse than the logs
SEC-L01 fixed in two ways: `console` output on Render rotates, whereas `adminAuditLog` is a
Firestore collection with **no TTL and no retention policy**; and the record is a *join*, not an
isolated value. Each entry ties an operator IP to a timestamp to a specific user UID to a
specific action, and `adminAuditLog` accumulates that indefinitely.

The most sensitive of these is `action: "duress_enrolled", uid: <raw uid>` (`:2818-2819`).
Whether a given account has a duress PIN is the single most dangerous fact in this system to
disclose to a coercive adversary, and it is now stored twice in cleartext — in
`duressEligibility/{uid}` by design, and again with a timestamp and an operator IP in
`adminAuditLog`, from which `revokedAt` (`:2861`) preserves the history even after revocation.

The four `console.log`s are the same violation in the ephemeral channel: `uid=${uid}` at `:2705`,
`:2815`, `:2862` and `requestId=${requestId}` at `:2632` should be `uidTag(uid)`. (The identical
pattern exists at `:2398` in `/requestLockNonce` and `:1577` in `/requestAccess` — Session 06's
and Session 02's respectively, but it is one systemic omission.)

**Honest severity discussion.** Medium, not High: reaching this data requires Firestore access,
i.e. an adversary who already holds the service account or a legal order against the project —
and such an adversary can read `duressEligibility` directly anyway. What this finding adds is
that the audit log *increases* the blast radius of that access (operator IP history, timing, and
a permanent record of revoked enrollments that would otherwise be gone) for no security benefit,
and that it does so in explicit contradiction of a control the codebase already implemented.

**Fix:** store `ipTag(getClientIp(req))` instead of the raw IP in all four audit writes — a
pepper-hashed tag is fully sufficient for "was this the same operator?"; replace the four
`console.log`s with `uidTag`; add a retention policy (Firestore TTL) on `adminAuditLog`; and
reconsider whether `duress_enrolled` needs to name the UID in the audit record at all, or whether
a hashed reference would serve the operator equally well. Note the tension with S05-H3, which
wants *more* durable audit: the resolution is pseudonymised-but-durable, not identifying.

---

## S05-M2 (Medium) — `duressEligibility` is enforced nowhere on the server, so the admin panel's enroll/revoke actions are cosmetic and the console displays a security state that does not exist

**Location:** `server/index.js:2789` (`enroll`), `:2838` (`revoke`), `:2772` (the status the
console reports), `:1178-1182` (how it is rendered); the missing enforcement is at
`:2362-2408` (`/requestLockNonce`) and `:2410+` (`/duress-lock`).

`POST /requestLockNonce` verifies an ID token (`:2378`) and applies a rate limit (`:2385`), then
issues a lock nonce. It **never reads `duressEligibility/{uid}`**. Neither does `/duress-lock`.
Grepping the server, `duressEligibility` appears only in the three admin routes that read and
write it — no other code path consults it. `firestore.rules:322` lets the account read its own
doc, and that is the only consumer: the Android client shows or hides the secondary-PIN setup UI
based on it.

Under this project's own threat model ("every client-side check is bypassable," README), a
client-side feature gate is not a control. Therefore:

- **Enrolling grants nothing** the account could not grant itself with a modified APK.
- **Revoking removes nothing.** The comment at `:2836-2837` concedes the flag is only re-read
  "on the next eligibility refresh (sign-in or foreground)", but the deeper problem is that even
  a perfectly-propagated `false` would not prevent anything server-side.
- **The console asserts a state the server does not hold.** `searchDuressAccount` renders
  "Duress PIN: enabled" / "not enabled" (`:1178-1179`) from `duressEligible` (`:2772`). An
  operator responding to an incident by clicking "Disable" receives a success toast (`:1218`)
  and a state change in the panel, and reasonably concludes the capability is gone. It is not.

**Honest severity discussion.** Medium. The direct security impact is genuinely low: a user
self-enabling a duress PIN harms only their own account, so this is not a path to another user's
data. It is rated Medium rather than Low because *false operator assurance during incident
response* is its own harm class — the panel is the tool an operator reaches for under pressure,
and it is reporting an enforcement boundary that does not exist. It also means an admin action
with a durable audit entry (S05-M1) has no corresponding effect, which will mislead a future
investigation.

**Fix:** decide which it is. If duress eligibility is meant to be a real control, enforce it
server-side — have `/requestLockNonce` (and any other duress-path endpoint) load
`duressEligibility/{uid}` and refuse when `eligible !== true`, and have Session 06 confirm the
whole duress flow is gated. If it is only a feature-flag for UI rollout, rename it and relabel
the panel so it does not read as a security state, and drop it from the audit log's
security-relevant actions.

---

## S05-M3 (Medium) — Admin sessions have no absolute lifetime, are refreshed by an unauthenticated request, are bound to nothing, and cannot be revoked in bulk; the inactivity timeout that appears to bound them is client-side only

**Location:** `server/index.js:595-606` (`hasValidAdminSession`), `:2552` (the unauthenticated
refresh), `:541` (TTL), `:1275-1301` (the client-side timeout), `:566-573` (the failure counter
that never resets).

```js
// server/index.js:603-605 — sliding expiry, with no ceiling
// Sliding expiry keeps an actively used admin panel open.
adminSessions.set(sessionId, Date.now() + ADMIN_SESSION_TTL_MS);
return true;
```

**Five compounding weaknesses:**

1. **No absolute cap.** Every touch extends the session another 30 minutes. A session created
   once can live indefinitely; there is no `createdAt`, so no maximum age can be enforced.
2. **An unauthenticated route performs the refresh.** `GET /admin` (`:2551`) requires no auth and
   calls `hasValidAdminSession(req)` at `:2552` purely to decide which view to render — but that
   call has the side effect of extending the TTL. Any request that carries the cookie keeps the
   session alive, including a browser prefetch, a restored tab, or a background reload. The
   session therefore does not expire from "operator inactivity" in any meaningful sense.
3. **Bound to nothing.** No IP, no User-Agent, no re-authentication for destructive actions. A
   cookie captured from a browser profile, a shared machine, or a synced session store is a full
   admin credential from anywhere, and the session id is not rotated on privilege use.
4. **The inactivity timeout is UI only.** `INACTIVITY_TIMEOUT_MS` (`:1275`), the countdown
   banner, and `forceLogout` all run in page JavaScript. The server-side TTL is 30 minutes and
   sliding. Per the project's own rule that client checks are never controls, the panel's
   10-minute auto-logout provides no bound on the session's actual lifetime — though credit
   where due, `forceLogout` does fire a real `POST /admin/logout` (`:1320`), so it is not purely
   decorative when the page is open.
5. **No bulk revocation, and the failure counter never resets on success.** Rotating
   `ADMIN_TOKEN` does not invalidate `adminSessions` (it is read once at module load, `:537`), so
   sessions minted under a leaked token survive its rotation until they idle out — which, per
   (1) and (2), may be never. Separately, a *successful* auth does not clear `adminIpFails`
   (`:565-573`), so nine typos leave the operator one mistake from a 15-minute self-lockout.

Cross-reference: `adminSessions` is in-memory (S04-L3), so sessions do not survive a restart and
are not shared across instances — an availability wrinkle, and single-instance operation is an
undocumented security invariant.

**Fix:** record `createdAt` and enforce an absolute maximum (e.g. 8 h) alongside the sliding
idle TTL; do not refresh on `GET /admin` (pass a `{refresh:false}` flag for the render check);
bind the session to `ipTag(getClientIp(req))` and reject on change; rotate the session id after
login and require re-authentication for unfreeze/enroll; add a "sign out everywhere" that clears
`adminSessions`, and clear it automatically when the configured token changes; clear the IP
failure record on successful authentication.

---

## S05-L1 (Low) — `/admin/api/account/lookup` is the one admin route that skips `validAdminUid`, so a slash-bearing `uid` reaches `.doc()` and can address an arbitrary four-segment document path

**Location:** `server/index.js:2756-2780`, specifically the check at `:2762`; contrast `:2692`,
`:2800`, `:2849`.

```js
// server/index.js:2761-2766 — length only; no character validation
const uid = requestUrl.searchParams.get("uid") || "";
if (!uid || uid.length > 128) { … 400 … }
…
db.collection("identities").doc(uid).get(),   // :2768
```

`validAdminUid` (`pure.js:34-39`) exists precisely to reject `/`, `\`, and control characters
before they reach a Firestore path, and the other three UID-taking routes use it. This one does
not. `CollectionReference.doc()` accepts a **slash-separated path**, so `?uid=a/b/c` resolves to
the document `identities/a/b/c` — a subcollection document — and the response's `accountExists`
field reports whether it exists. That is an existence oracle over arbitrary nested paths under
`identities`, plus a second one via `duressEligibility` (`:2769`).

Impact is genuinely small: it is post-authentication, read-only, returns a single boolean, and
odd-segment paths (`a/b`) throw and land on a 500 via `sendServerError`. Reserved ids (`.`,
`..`, `__x__`) likewise throw — `validAdminUid` does not cover those either, so the same 500 is
reachable on the other routes. Minor related nit: the route matches with `startsWith`
(`:2756`), so `/admin/api/account/lookupanything` also routes here, unlike every sibling route's
exact `===` match.

**Fix:** apply `validAdminUid` to the query-string `uid` as the other three routes do; extend
`validAdminUid` to reject `.`, `..`, and `__…__` so malformed ids yield 400 rather than 500;
match the path with `new URL(req.url).pathname === "/admin/api/account/lookup"`.

---

## S05-L2 (Low) — `collectBody` runs before `requireAdminAuth` on all four admin POST routes, the opposite of what the helper's own comment claims

**Location:** `server/index.js:2604-2605`, `:2682-2683`, `:2790-2791`, `:2839-2840`; the comment
that describes the intended order is at `:770-779`.

```js
// server/index.js:770-773 — the stated rationale for collectBody existing
// Callback-style counterpart to readBody(), for handlers written in the
// on("data")/on("end") style (several routes below need to run
// requireAdminAuth or other checks before body parsing …
```

```js
// server/index.js:2604-2605 — but the auth check is *inside* the end callback
collectBody(req, res, async (body) => {
  if (!requireAdminAuth(req, res)) return;
```

`requireAdminAuth` therefore does not run until the entire body has been received. An
unauthenticated caller can make the server buffer up to 64 KB per request, and — because the
handler is waiting on `"end"` — hold a socket for as long as Node's default `requestTimeout`
(300 s in Node 20, which `package.json:115` pins) allows, by trickling bytes. No `server.timeout`
/ `requestTimeout` / `headersTimeout` override is set anywhere in the file, so the defaults
apply. Cheap for the attacker, and it costs an unauthenticated party nothing to keep many such
sockets open against a single-process server that also carries FCM delivery.

Also note `adminIpLocked` is evaluated at this late point, so a locked-out IP still gets to
stream a body before receiving its 429.

**Fix:** call `requireAdminAuth(req, res)` *before* `collectBody`, then destroy the request on
failure — the auth check reads only headers and cookies, so nothing forces it to wait for the
body. Set explicit `server.requestTimeout` / `headersTimeout` values. Update or delete the
now-inaccurate comment at `:770-779`.

---

## S05-L3 (Low) — No `Cache-Control` on any `/admin/api/*` response, so the locked-account list, the duress-enrolled list, and the audit log are ordinarily cacheable

**Location:** `server/index.js:2589`, `:2667`, `:2741`, `:2773`, `:2898` (each
`writeHead(200, { "Content-Type": "application/json" })`), and
`setBaselineSecurityHeaders` (`:1388-1406`), which sets seven security headers but never
`Cache-Control`.

`GET /admin` correctly sets `no-store, no-cache, must-revalidate` plus `Pragma`/`Expires`
(`:2559-2561`), which shows the concern was understood — the JSON endpoints simply did not get
the same treatment. A `200` with no cache directives and no `Vary` is heuristically cacheable:
these responses can land in the browser's on-disk cache (recoverable from an operator's machine
or backup, which matters for a tool whose threat model includes device seizure), and would be
stored by any future intermediary — note that S04-M3 flags "operator puts Cloudflare in front of
the API server" as a realistic change, and a shared cache serving `/admin/api/duress/enrolled`
would be considerably worse than a disk-cache entry.

Related, same lines: none of these routes sets `Vary: Cookie`, and authorization here depends on
a cookie.

**Fix:** add `Cache-Control: no-store` (and `Vary: Cookie`) to the baseline headers for all
`/admin*` responses — or simply to `setBaselineSecurityHeaders`, since no route in this server
benefits from caching.

---

## S05-L4 (Low) — Read-then-write TOCTOU on approve and unfreeze, and two unbounded `.get()` queries with no `limit()`

**Location:** `:2620-2631` (approve), `:2698-2704` (unfreeze); `:2659-2661`
(`/admin/api/locked`), `:2732-2734` (`/admin/api/duress/enrolled`).

**TOCTOU.** Both mutating routes `get()`, branch on the result, then `update()`/`delete()`
outside any transaction. Two concurrent approvals of the same `requestId` both observe
`status === "pending"` and both write `approved`; two concurrent unfreezes both see the doc and
the second `delete()` is a no-op. The consequences are benign today — the operations are
idempotent and the real single-use enforcement lives in `/mintToken`'s transaction (`:1483`,
correctly) — but each race produces **two** audit entries for one effective action, which
corrupts exactly the record S05-H3 is asking to be made authoritative. Contrast the rest of the
server, which uses `db.runTransaction` for state that matters (`:1483`, `:2446`).

**Unbounded reads.** `accountLock where locked == true` and
`duressEligibility where eligible == true` are fetched with no `limit()` and no pagination, unlike
the waitlist query (`limit(200)`, `:2582`) and the audit log (`limit(100)`, `:2891`). Clients
*can* create their own `accountLock` doc (`firestore.rules:344`), so the locked set grows with
the user base and with any attacker holding multiple accounts; the whole result set is
materialized into a single JSON response in a single-process server. Slow degradation rather than
a break, and the panel silently loses usability long before that.

**Fix:** wrap approve and unfreeze in `db.runTransaction` (which also gives S05-H3 its atomic
audit write for free); add `limit()` + pagination to both list endpoints and surface a total
count.

---

## S05-I1 (Info) — `server/README.md` documents neither `ADMIN_TOKEN` nor the other operator secrets, and the server boots silently without them

`server/README.md` lists `GOOGLE_APPLICATION_CREDENTIALS_JSON` and one optional tuning variable.
Absent entirely: `ADMIN_TOKEN` (`:537`), `TURN_TOKEN_ID` / `TURN_API_TOKEN` (`:2061-2062`),
`B2_KEY_ID` / `B2_APPLICATION_KEY` / `B2_BUCKET` / `B2_REGION` (`:2918-2921`, and per S04-I2 the
B2 ones should be deleted and the key revoked). There is no startup validation and no startup
log line for any of them — the admin panel's absence surfaces only as a 503 the first time an
operator tries to log in (`:620`), and there is no guidance anywhere on how to generate the
token. This is the documentation half of S05-H1 and should be fixed with it.

---

## S05-I2 (Info) — Comments and docs describe an admin surface that no longer exists, in ways that mislead a reviewer

Four concrete drifts, each pointing a reader at the wrong mechanism:

- `:801-803` — "Prompts for the operator token once, keeps it in memory only … and sends it as
  `x-admin-token` on every fetch." The page no longer holds the token at all: `TOKEN` is `""`
  (`:954`) and never assigned, and authentication is the `HttpOnly` session cookie. The current
  design is *better* than the comment describes, which means the comment understates the
  posture — but it also means a reviewer looking for the token in page JS wastes time.
- `:2600`, `:2653`, `:2678`, `:2726`, `:2752`, `:2784`, `:2835`, `:2882` — every admin route's
  docblock says "Auth: x-admin-token header". In practice the browser path always authenticates
  by cookie; the header is a secondary mechanism for scripted access.
- `firestore.rules:317-318` — "the operator writes this doc via the Firebase console / Admin SDK
  — no client … may create, modify, or delete it." Accurate about clients, but predates
  `/admin/api/duress/enroll`; a reader auditing how eligibility is granted will not find this
  API from the rules file. Same for `:1554` ("Firebase console / admin script — never from the
  app") versus `/admin/api/waitlist/approve`.
- `:2883` — "tamper-evident record" (see S05-H3) and `:2882` "waitlist approvals + account
  unfreezes", which omits the two duress actions the log now records.

---

## S05-I3 (Info) — CSRF protection rests entirely on `SameSite=Strict`; the `Secure` cookie flag is derived from a client-supplied header; and `safeTokenEqual` has a theoretical length oracle

Three small hardening notes, none exploitable as written:

- **CSRF.** The mutating routes accept either the `x-admin-token` header or the session cookie
  (`:624-625`) and never check an anti-CSRF token, `Origin`, `Sec-Fetch-Site`, or even that
  `Content-Type` is `application/json`. `SameSite=Strict` (`:584`) does block the cross-site
  case in every current browser, and CSP `form-action 'self'` (`:1383`) covers form posts, so
  this is defense-in-depth rather than a live gap — but it is a single-mechanism defense on the
  routes that delete `accountLock` documents. A `Sec-Fetch-Site: same-origin` assertion is one
  line.
- **`Secure` from an untrusted input.** `adminSessionCookie` decides whether to set `Secure`
  based on `x-forwarded-proto` (`:582-583`), which any client can send. A client cannot use this
  to attack anyone else — it only affects the cookie issued to itself — but the flag on the
  system's most privileged cookie should not be derived from attacker-controlled input. Prefer an
  explicit `TRUST_PROXY` / `FORCE_SECURE_COOKIES` config (pairs with S04-M3's
  `TRUSTED_PROXY_HOPS`), defaulting to `Secure` in production.
- **Length oracle.** `safeTokenEqual` (`pure.js:26-28`) returns early on a length mismatch and
  only reaches `timingSafeEqual` on equal lengths, so response time is in principle a function of
  whether the supplied token's length is correct. Extracting one integer from a very noisy
  network timing channel is not a practical attack, and it needs many samples — though note
  S05-H1 means the sample budget is effectively unlimited. Hashing both sides to a fixed width
  before comparing removes it entirely.

---

## Handoff notes

- **Session 04 re-rating (action required).** Session 04 deferred S04-M1's severity to this
  session: "assess `ADMIN_TOKEN` entropy/rotation there; if it is not high-entropy, re-rate
  S04-M1 upward." **Verdict: nothing enforces, validates, or documents entropy** (S05-H1,
  S05-I1). S04-M1 should be recorded as **High** in Session 10, since the IPv6-rotation bypass of
  `adminIpFails` is the removal of the only ceiling on guessing the only admin factor.
- **Session 06 (duress & locks):** S05-M2 is yours to close — confirm whether *any* server path
  gates on `duressEligibility`; my read of `/requestLockNonce` (`:2385`) and `/duress-lock` says
  none does. Also inherit: `/requestLockNonce` logs a raw uid at `:2398` (same violation as
  S05-M1), and the `accountLock` one-way latch's only escape hatch is
  `/admin/api/locked/unfreeze`, whose authentication is S05-H1 and whose audit trail is S05-H3 —
  assess the latch end-to-end with that in mind.
- **Session 09 (supply chain / CI):** S05-I1 — check whether `ADMIN_TOKEN` or any operator secret
  appears in CI config, Render blueprints, or `.env` examples, and whether a rotation procedure
  exists anywhere.
- **Session 10 (synthesis):** three items to carry. (1) The S04-M1 re-rating above.
  (2) S05-H2 is a *design* finding, not a bug — it should be reported as "the invite gate does
  not gate," and it interacts with S04-M1's free `/requestAccess` writes; the two are one story.
  (3) S05-M1 vs S05-H3 pull in opposite directions (more durable auditing vs. less identifying
  data retained) — the synthesis should recommend pseudonymised-but-atomic auditing rather than
  letting the two findings cancel out.
- **Positive note for the final report:** the admin panel's *implementation* quality is high —
  nonce CSP, no token in page JS, `textContent` everywhere, constant-time compare, correct cookie
  flags, server-side logout, fail-closed when unconfigured. Every finding in this session is
  about the surface's authentication model, its inputs, or its records — none is an
  implementation flaw in the panel itself. That distinction is worth preserving in the summary.
