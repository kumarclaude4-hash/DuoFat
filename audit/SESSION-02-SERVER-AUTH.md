# SESSION 02 — Server Auth & Identity Core

**Scope:** the server-side root of trust in `server/index.js` — the token-minting and
identity endpoints and their shared auth/rate-limit helpers:

- `POST /mintToken` (`:1436`) + `POST /requestAccess` (`:1558`) + `GET /waitlistStatus` (`:1594`)
- `POST /migrateUid` (`:1647`)
- `POST /createChat` (`:1839`)
- helpers: `checkAuthRateLimit` (`:430`), `checkIpRateLimit` (`:693`), `getClientIp` (`:643`),
  `mintCooldown` (`:343`), `sha256hex` (`:705`), `collectBody`/`readBody` (`:750`/`:780`)
- backing rules: `identities` (`firestore.rules:252`), `waitlist` (`:368`), `backups` (`:282`)

**Threat model (from RECON):** attacker holds one or more authenticated accounts, can call
every endpoint directly with forged bodies/headers, automates abuse across many IPs/devices,
and is willing to burn accounts. The Admin SDK bypasses Firestore rules, so this server code
*is* the authorization boundary (TB-1/TB-3/TB-6).

**Result:** 0 Critical / 1 High / 1 Medium / 4 Low / 3 Info. Second-pass additions (marked
`[P2]`): S02-H1 (migration blindly copies all user-doc fields — field-injection escalation
path), S02-L4 (collectBody uses string `.length` not byte count — up to 2× body-size-cap
bypass via multi-byte UTF-8). The core identity model is sound — a single mint path, atomic
slot-claim + waitlist consumption, migration that cannot retarget another account, and correct
token→uid binding on every endpoint.

---

## Verified-correct (positive assurances)

These were attacked and held; recording them so Session 10 need not re-derive them.

- **Single mint path.** `createCustomToken` appears exactly once (`:1523`), inside `/mintToken`
  after the atomic identity claim. No anonymous-auth bootstrap or secondary mint exists, so an
  attacker cannot obtain `auth.uid == <someone else's userId>`. Every other endpoint only
  *verifies* ID tokens (`:1660,:1853,:1965,:2047,:2221,:2315,:2378`).
- **Atomic new-account claim (F2).** `/mintToken` claims `identities/{userId}` and consumes the
  approved `waitlist` doc in one transaction (`:1483-1519`); the waitlist doc is flipped to
  `used` in the same tx (`:1499-1503`), so one invite can never mint two accounts, and
  concurrent first-claims serialize.
- **Waitlist is server-only.** `firestore.rules:369` denies all client read/write to `waitlist`,
  so a client cannot self-approve; approval is Admin-SDK/operator only. The 32-hex `requestId`
  format is enforced on both mint (`:1489-1490`) and status poll (`:1608`).
- **Migration cannot retarget another account.** `/migrateUid` requires `decodedToken.uid ==
  userId` (`:1681`), then gates on the stored identity: `storedUid === userId` → idempotent
  no-op (`:1704`), `storedUid !== oldUid` → 403 (`:1712`). The attacker-supplied `oldUid` is only
  ever honored when it already equals the caller's own identity record, so it cannot rewrite a
  victim's `users`/`backups`/chat/group data. Array swaps are transactional + idempotent
  (`:1769,:1784`); the `uid` completion marker is written last (`:1797`) so retries stay authorized.
- **createChat binding.** Verifies token, `decodedToken.uid == myUid` (`:1874`), and that both
  UIDs exist in `identities` (`:1881-1894`); `chatId` is a deterministic SHA-256 of the sorted
  pair and the write is an idempotent `merge` (`:1908`). Client-side chat `create` is denied in
  the rules, so this is the only creation path (F6).
- **IP source is not client-spoofable.** `getClientIp` uses the **rightmost** XFF entry
  (proxy-appended by Render), not the client-controlled leftmost (`:643-655`, CRIT-1 fix).
- **Body bounds.** Both `readBody` and `collectBody` enforce `MAX_BODY_BYTES = 64 KB` even for
  chunked requests with no Content-Length (`:748-797`).
- **Log hygiene.** IPs/UIDs are HMAC-pseudonymised with a per-process pepper (`:666-677`) and
  errors return a correlation ref, not `e.message` (`:685-691`).

---

## S02-M1 (Medium) — `/mintToken` per-userId cooldown is set pre-authentication → targeted re-auth DoS

**Location:** `server/index.js:1462-1469`

```js
const now  = Date.now();
const last = mintCooldown.get(userId) || 0;
if (now - last < 60_000) { res.writeHead(429); res.end("... wait 60 s ..."); return; }
mintCooldown.set(userId, now);          // ← set for ANY caller-supplied userId,
                                        //   before the identity/hash transaction runs
```

**Trust boundary:** TB-1 (client ↔ auth mint). `userId` is fully attacker-controlled and, per
S01-I1, enumerable (`identities`/`users`/chat participants are world-readable to any authed user).

**Exploit path:** an attacker sends `/mintToken` with a *victim's* `userId` and a bogus
`identityPubKeyHex`. The cooldown is stamped at `:1469` **before** the transaction, and the
request only later fails the in-transaction hash check with 403 (`:1515`). Net effect: a failed,
unauthenticated-to-that-identity attempt still consumes the legitimate owner's 60 s mint window.
Repeating once per 60 s keeps the victim permanently at 429, blocking them from re-authenticating
/ restoring their account on a new device — a meaningful availability hit on a safety tool where
recovery may be time-critical (e.g. after device loss or a duress event).

**Why it's only Medium, stated honestly:** the per-IP limiter (`checkIpRateLimit`, 5/15 min at
`:1441`) caps a single IP to poisoning ~5 userIds per window, and sustaining one victim needs
~15 hits/15 min, so continuous denial requires IP rotation — realistic under the "abuse at
scale / many IPs" model but not free. The in-memory limiter also resets on Render cold start
(see S02-I2), which if anything *helps* the attacker (limits are best-effort).

**Fix:** do not let an unauthenticated/failed attempt burn the owner's window. Either
(a) stamp the cooldown **only after** the transaction confirms a legitimate mint (accepting the
original concurrency note by keying the in-flight guard on a short-lived per-userId promise/lock
rather than a timestamp), or (b) track failed-hash attempts in a **separate** bucket keyed by
`ipTag(clientIp)` so a wrong-key caller cannot touch the real owner's cooldown. The success-path
cooldown should be keyed to a *verified* identity, never to an unverified request parameter.

---

## S02-L1 (Low) — Existing-account hash check fails open when stored hash is falsy

**Location:** `server/index.js:1514-1517`

```js
const storedHash = snap.data().identityPubKeyHash;
if (storedHash && storedHash !== incomingHash) {   // ← skipped entirely if storedHash is falsy
  throw Object.assign(new Error("Key mismatch"), { status: 403 });
}
```

**Issue:** if an `identities/{userId}` doc ever exists **without** a truthy `identityPubKeyHash`,
the key-continuity check is bypassed and a token is minted for *any* supplied key. The
client-side `identities` create rule (`firestore.rules:254-256`) notably requires only
`uid == userId` and does **not** require `identityPubKeyHash`, so a hash-less identity doc is a
shape the rules explicitly permit.

**Reachability (why Low, not High):** to create such a doc a client needs `auth.uid == userId`,
which today only comes from `/mintToken` (which always writes the hash). An attacker cannot make
their auth uid equal a *victim's* userId, so this is not currently a cross-account takeover — it
is a latent fail-open that would become exploitable if any future path (a new admin tool, an
alternate auth provider, a partial migration) produced a hash-less identity.

**Fix:** fail closed — treat a missing/empty `storedHash` on an existing doc as an error
(`if (!storedHash || storedHash !== incomingHash) throw 403`), and tighten the rules' `identities`
create to require `request.resource.data.identityPubKeyHash is string`.

---

## S02-L2 (Low) — `createChat` stores attacker-controlled display names unsanitized and unbounded

**Location:** `server/index.js:1905-1908`

```js
if (myDisplayName)      chatDocData["partnerName_" + partnerUid] = myDisplayName;
if (partnerDisplayName) chatDocData["partnerName_" + myUid]      = partnerDisplayName;
```

**Issue:** `myDisplayName`/`partnerDisplayName` are caller-supplied and written verbatim into the
chat doc as the name the *other* party will see for the caller. There is no type check, length
cap (beyond the 64 KB body), or content sanitisation. A registered attacker can seed any victim's
chat list with an attacker-chosen "contact name" string that the victim's client later renders
(list rows, notifications). This is a stored client-render / notification-injection surface
(defense-in-depth; actual impact depends on client rendering — Session 07/08).

**Secondary:** the field *name* is built from `partnerUid`/`myUid`. Both are verified to be
existing `identities` doc IDs (real seed-derived hex, no `.`), so field-path injection is not
currently reachable, but constructing Firestore field names from request-derived strings is
fragile — validate the UID charset explicitly.

**Fix:** `typeof x === "string"` + length cap (e.g. ≤ 128) on both display names; validate
`myUid`/`partnerUid` against `^[0-9a-f]{16,64}$` before using them in field paths.

---

## S02-L3 (Low) — `mintCooldown` map has no purge timer (unbounded growth)

**Location:** `server/index.js:343` (declaration); contrast the `setInterval` purges for
`ipHits` (`:398`), `waitlistIpHits` (`:358`), and `authRateLimits` (`:452`).

**Issue:** `mintCooldown.set(userId, now)` runs for every caller-supplied `userId` (`:1469`) and
is **never** cleaned up, unlike every sibling limiter map which has a periodic purge. Entries are
only 60 s-relevant but live for the whole process lifetime. Because the key is attacker-controlled
(any string), an attacker who cycles distinct `userId` values grows the map without bound — a slow
memory-exhaustion vector, and a conspicuous inconsistency with the other limiters.

**Fix:** add a `setInterval` purge dropping entries older than the 60 s window (mirroring
`:398-403`), and/or cap the map size.

---

## S02-I1 (Info) — Cold-contact + registration oracle in `createChat` (by design; cross-ref S01-I1)

`createChat` lets any registered user create a chat doc pairing themselves with **any** other
registered user without consent (`:1902-1908`); combined with the participant-scoped message
`create` rule this enables unsolicited first contact / spam. It also returns distinguishable
statuses — `404 "Partner not found"` vs success (`:1890-1894`) — confirming whether a given
`userId` is a live account. This adds no capability beyond the already world-readable `identities`
oracle (S01-I1) but is the server-side face of the same trade-off. Decision needed with S01-I1:
accept as inherent to a messenger, or gate first contact behind a mutual/invite handshake.

## S02-I2 (Info) — In-memory limiters are best-effort (non-durable, per-instance); cross-ref Session 04

`mintCooldown`, `ipHits`, `waitlistIpHits`, and `authRateLimits` are all process-local Maps. They
reset on Render cold start and are not shared across instances, so every rate/cooldown control in
this session (including the S02-M1 mitigation and the mint IP cap) is a soft speed bump, not a
hard guarantee. Full evaluation (durable store / shared limiter) is owned by **Session 04**;
recorded here because it directly bounds S02-M1's real-world severity.

## S02-I3 (Info) — ID-token verification does not use `checkRevoked`; disabled/locked sessions live to token expiry

Every `verifyIdToken(...)` call (`:1660,:1853,:1965,:2047,:2221,:2315,:2378`) omits
`checkRevoked: true`. A Firebase ID token stays valid for ~1 h, so after an account is
frozen/locked or its sessions revoked, existing tokens continue to pass server auth for up to an
hour on all these endpoints. Whether that matters depends on the account-lock/duress design
(**Session 06** owns the latch). If lock is meant to be immediate, the high-value endpoints should
verify with `checkRevoked: true` (or check a server-side lock flag) rather than trusting token
lifetime. Recorded here as an auth-core observation.

---

---

## S02-H1 `[P2]` — `migrateUid` copies `users/{oldUid}` verbatim; arbitrary fields injected by the attacker onto their old UID doc are promoted to the new deterministic UID doc

**Location:** `server/index.js:1730-1736`

```js
const oldUserSnap = await db.collection("users").doc(oldUid).get();
if (oldUserSnap.exists) {
  const data = oldUserSnap.data();
  if (data) {
    await db.collection("users").doc(userId).set(data);   // ← no field allowlist
```

**Trust boundary:** TB-1/TB-6. **Severity: High.**

`oldUid` is the caller's *current* Firebase UID, which is their *own* account — so
`users/{oldUid}` is fully writeable by the caller before migration (`firestore.rules:9`:
`allow write: if request.auth.uid == uid`). They can plant any Firestore fields they like on
their own user doc, then call `/migrateUid` to have those fields transplanted verbatim onto the
new deterministic `users/{userId}` doc with no field filtering.

**Exploit path:**
1. Before calling `/migrateUid`, the attacker writes their old user doc:
   `users/{oldUid} = { displayName:"X", fcmToken:"...", isAdmin:true, role:"admin", verified:true, ... }`.
2. `/migrateUid` calls `db.collection("users").doc(userId).set(data)` — all planted fields land
   on the production `users/{userId}` doc, indistinguishable from server-written fields.
3. Any server or client logic that reads `users/{userId}` and trusts a field like `isAdmin`,
   `role`, or `verified` now grants the elevated role to the migrated account.

**Current severity qualifier:** whether this is exploitable for privilege escalation today
depends on which fields the app/server treat as authoritative from `users/{uid}`. If nothing
currently reads `isAdmin`/`role` from `users`, the impact is confined to display-data injection
(similar to S02-L2) — but the architectural hole is real and will become critical if any
privileged field is ever added to user docs. Severity is rated **High** because the pattern is
an unconditional field injection into a production identity document from an attacker-controlled
source, and the cost of accidentally trusting one future field is account compromise.

**Fix:** replace the blind `set(data)` with an explicit field allowlist:

```js
const ALLOWED_USER_FIELDS = new Set(['displayName', 'fcmToken', 'avatarUrl', 'createdAt']);
const safeData = Object.fromEntries(
  Object.entries(data).filter(([k]) => ALLOWED_USER_FIELDS.has(k))
);
await db.collection("users").doc(userId).set(safeData);
```

---

## S02-L4 `[P2]` — `collectBody` measures string character count, not byte count; multi-byte UTF-8 allows up to ~2× bypass of the 64 KB body cap

**Location:** `server/index.js:786`

```js
function collectBody(req, res, onComplete) {
  let body = "";
  ...
  req.on("data", (chunk) => {
    body += chunk;                          // Buffer coerced to UTF-16 JS string
    if (body.length > MAX_BODY_BYTES) {     // ← .length = char count, not bytes
```

`body.length` on a JavaScript string counts UTF-16 code units, not bytes. A Node.js `Buffer`
coerced to a string via `+=` is decoded as UTF-8; the resulting JS string has `.length` equal
to the number of code points (or surrogate pairs for > U+FFFF), which is less than the byte
count for any multi-byte sequence. A payload composed entirely of 2-byte UTF-8 sequences
(e.g. `U+0080`–`U+07FF`) has a byte size twice its `.length`, so the 64 KB cap is effectively
raised to ~128 KB for such payloads.

**Compare:** `readBody` at `:754` correctly accumulates byte count separately:
`bytes += chunk.length` (Buffer `.length` = bytes), checking bytes not the string — it is
**not** affected.

**Impact:** an attacker can send a ~128 KB JSON body through any `collectBody`-protected
endpoint (`/mintToken`, `/migrateUid`, `/createChat`, `/mediaToken`, etc.), up to ~256 KB for
4-byte sequences. This is not catastrophic — a 256 KB JSON object won't cause OOM — but it
undermines the stated DoS defense and creates room for oversized field values to be written
to Firestore (where documents have a 1 MB limit but any path is more expensive than expected).

**Fix:** track byte count from the raw chunks, same as `readBody`:

```js
let bytes = 0;
req.on("data", (chunk) => {
  bytes += chunk.length;           // Buffer.length = bytes
  body  += chunk;
  if (bytes > MAX_BODY_BYTES) { ... }
});
```

---

## Handoffs

- **Session 04:** durability/scope of all in-memory limiters (S02-I2); mint IP cap effectiveness.
- **Session 06:** whether account-lock must revoke live ID tokens → `checkRevoked` decision (S02-I3).
- **Session 07/08:** client rendering of `partnerName_*` and other server-stored strings (S02-L2).
- **Session 10:** re-confirm F2 atomic claim, F6 server-only chat creation, and CRIT-1 XFF fix
  remain intact; fold S01-I1 + S02-I1 into a single "enumeration/cold-contact" product decision.

_End of Session 02. Next: Session 03 — Media capability tokens + Storage Worker (TB-4/TB-9)._
