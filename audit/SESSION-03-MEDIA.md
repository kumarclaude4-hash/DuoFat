# SESSION 03 — Media Capability Tokens & Storage Worker (TB-4 / TB-8 / TB-9)

**Scope:** the entire media data plane — the only place where a *non-Firebase* service
(Cloudflare Worker → R2/B2) is asked to authorize a per-user operation:

- `POST /mediaToken` (`server/index.js:1942`) + its helpers `signMediaToken` (`:495`),
  `callerMayAccessScope` (`:509`), `MEDIA_KEY_FORMAT` (`:480`), `MEDIA_TOKEN_TTL_MS` (`:475`),
  the `mediaToken` rate bucket (`:426`)
- `worker/src/index.js` end to end: `verifyMediaToken` (`:146`), `isAuthorized` (`:76`),
  key allow-list (`:409`), quota/rate gates (`:224`, `:271`), PUT/GET/DELETE handlers
  (`:439`/`:486`/`:530`), the nightly R2→B2 `scheduled` job (`:587`)
- backing authorization data: `chats.participants` and `groups.members`
  (`firestore.rules:43`, `:98`)
- residual APK secret plumbing (`app/build.gradle:71-77`) and the client's token exchange
  (`app/src/main/java/com/duoshield/app/util/B2StorageHelper.java:1448-1500`)

**Threat model applied:** the attacker holds one or more real accounts, can call `/mediaToken`
and the Worker directly with forged bodies/headers, can write any Firestore document the rules
permit (that is *part of* the attack, not an assumption of compromise), and will automate abuse.
The APK is decompiled. Only the server and the Worker count as controls.

**Result: 0 Critical / 3 High / 3 Medium / 4 Low / 3 Info.**

The SEC-A01 rewrite itself holds up under attack — the token cryptography, its binding to
(key, verb, expiry) and the fail-closed posture are all correct, and I could not forge, widen,
or replay a token across objects or verbs. **Every finding below is in what the token is
*authorized against*, not in the token itself:** the participation check can be satisfied by a
Firestore document the attacker is allowed to create (S03-H1), and neither the server nor the
Worker enforces any *aggregate* per-user bound, so a single account can deny media to the whole
user base two different ways (S03-H2, S03-H3).

---

## Verified-correct (positive assurances — SEC-A01 re-verification for Session 10)

The prior review's SEC-A01 remediation was re-derived from scratch, not assumed:

- **No shared data-plane secret remains.** `WORKER_SECRET` gates only `/stats`
  (`worker/src/index.js:362-368`); every object operation goes through `verifyMediaToken`
  (`:425`). `BuildConfig.WORKER_SECRET` has **zero** runtime references in the app (grepped
  `app/src` — only comments), so the extractable-credential data-plane hole is genuinely closed.
  (Its continued *presence* in the APK is S03-L1.)
- **Both secrets fail closed.** Missing `WORKER_SECRET` → deny (`worker:77-80`); missing
  `MEDIA_TOKEN_SECRET` → 503 on the Worker (`worker:147-150`) and 503 on the server
  (`server:1945-1953`). No "open mode" fallback survives anywhere.
- **The signature covers the key, and the key is not carried in the token.** Wire format is
  `v1.<op>.<exp>.<uidTag>.<sig>` over `v1|op|exp|holder|key` (`server:497`), and the Worker
  re-derives the payload using the key **from the request path** (`worker:179`). A token
  captured for one object therefore authorizes nothing on any other object — the core SEC-A01
  claim is true.
- **Verb binding works.** `METHOD_TO_OP` (`worker:122`) + the `op !== expectedOp` check
  (`worker:168`) means a read token cannot be replayed as a DELETE. An unmapped method is 405
  *before* any storage access.
- **Constant-time comparisons, correct length handling.** `isAuthorized` (`:91-98`) and the
  signature check (`:186-191`) both length-check first and then use
  `crypto.subtle.timingSafeEqual`; malformed base64url is caught (`:183-184`) rather than
  crashing. `holder` is inside the signed payload, so it cannot be tampered with.
- **Auth precedes quota.** `verifyMediaToken` runs before `checkDailyRequestLimit` and the
  rate limiter (`worker:425-436`), so an *unauthenticated* flood cannot burn the global request
  budget. (An *authenticated* one can — S03-H2.)
- **Key allow-list blocks traversal.** `KEY_FORMAT` (`worker:409`) and the byte-identical
  `MEDIA_KEY_FORMAT` (`server:480`) reject `../`, null bytes, absolute paths, and arbitrary
  prefixes; validating on both sides means a malformed key never gets a signature at all.
- **Declared size is not trusted.** The `Content-Length` pre-check (`worker:443-447`) is backed
  by a post-upload `HEAD` and a delete-on-oversize (`worker:466-477`), and the R2 counter is
  adjusted with the *actual* stored size (`:480`).
- **B2 response headers are allow-listed** (`worker:511-521`) — no `x-amz-*` infrastructure leakage.
- **DELETE does not lie.** B2 network/non-404 failures return 502 rather than a false 200
  (`worker:556-566`), and the R2-branch fires a best-effort B2 delete to close the
  migration-window orphan race (`:539-548`).
- **Migration is etag-guarded.** The cron re-HEADs and only deletes from R2 when the etag is
  unchanged (`worker:646-664`), so a concurrent client PUT cannot cause data loss; and
  `getB2TotalBytes` returns `null` on error so a failed list never zeroes a valid counter (`:328-331`).

---

## S03-H1 (High) — Scope confusion: any user can mint media capability tokens for **another conversation** by creating a `groups/{id}` document whose ID is that conversation's ID

**Location:** `server/index.js:509-530` (`callerMayAccessScope`), reached from `:2005-2012`;
enabling rule: `firestore.rules:99-100`.

```js
// server/index.js:512-523
const [chatDoc, groupDoc] = await Promise.all([
  db.collection("chats").doc(scopeId).get(),
  db.collection("groups").doc(scopeId).get(),
]);
if (chatDoc.exists) {
  const participants = chatDoc.data().participants;
  if (Array.isArray(participants) && participants.includes(uid)) return true;
}
if (groupDoc.exists) {                       // ← fall-through, not an else
  const members = groupDoc.data().members;
  if (Array.isArray(members) && members.includes(uid)) return true;
}
```

```
// firestore.rules:99-100 — client-chosen document ID, self-asserted membership
match /groups/{groupId} {
  allow create: if request.auth != null
                && request.auth.uid in request.resource.data.members;
```

**Trust boundary:** TB-4 (client ↔ Storage Worker). The `/mediaToken` participation check *is*
the entire authorization model for media; the Worker only verifies that the server signed it.

**Root cause:** the object key is `<media|voice>/<scopeId>/<uuid>.<ext>` — the middle segment is
an **untyped** identifier. The server does not know (and cannot tell from the key) whether
`scopeId` is meant to name a chat or a group, so it accepts membership evidence from *either*
collection. `chats` creation is server-only (`firestore.rules:44`, the F6 fix) and chat IDs are
deterministic SHA-256 of the sorted UID pair — but `groups` creation is fully client-driven with a
**client-chosen document ID** and **self-asserted `members`**. Nothing anywhere requires the
document ID of a group to be unrelated to a chat ID.

**Exploit path (all steps are permitted by the rules as written):**

1. Attacker enumerates a victim `userId` (`identities` is world-readable to any authed user —
   S01-I1) and derives the deterministic `chatId` for the victim and any peer (the algorithm is
   in the APK; `server/index.js:1902` shows the same SHA-256-of-sorted-pair construction).
2. `groups/{thatChatId}` does not exist as a *group*, so the attacker creates it:
   `{ members: [attackerUid], createdBy: attackerUid }` — `firestore.rules:100` is satisfied.
3. Attacker calls `POST /mediaToken { key: "media/<thatChatId>/<uuid>.jpg", op: "write" }`.
   `MEDIA_KEY_FORMAT` passes; `callerMayAccessScope` finds no chat membership, **falls through**
   to the shadow group, sees itself in `members`, and returns `true` (`:520-523`).
4. The server signs a token for the victim's media namespace. The Worker verifies it correctly —
   it has no way to know the scope was forged.
5. Repeat with `op: "read"` / `op: "delete"` for any key under that scope.

The same construction works against a **group whose document has been deleted**: `allow delete`
at `firestore.rules:119-120` lets the creator remove the group doc, after which *any* user —
including a member the creator previously removed — can re-create `groups/{sameGroupId}` with
themselves in `members` and regain read/delete tokens for every object key of that group they
already know. Removal from a group is therefore **not durable** with respect to media.

**Honest severity discussion.** Rated **High**, not Critical, because bulk *confidentiality* loss
needs the object key's random UUID, which is not derivable from Firestore for a conversation the
attacker was never in (`chats/{id}` read and `chats/{id}/messages` read are both participant-gated,
`firestore.rules:45,64`). What the attacker gets unconditionally is:

- **Write into another conversation's namespace** — arbitrary objects under the victim's prefix
  (also the delivery vehicle for S03-H3's storage exhaustion, and for S03-M2's content-type abuse).
- **Read/overwrite/delete of any key they *do* learn** — which is exactly the ex-group-member case
  above, where the keys are already known, and any case where a key is logged, screenshotted,
  proxied, or backed up.
- **The control itself is bypassed.** "The server only issues a token after confirming the caller
  participates in that chat or group" (`worker/src/index.js:104-107`) is simply not true today,
  and the *only* thing standing between a forged scope and another user's media is UUID entropy —
  a secrecy-of-identifier control that the design explicitly refuses to rely on elsewhere
  (see the SEC-A01 rationale at `server/index.js:1930-1932`: keys "travel through Firestore chat
  documents, so it is not a secret in any strong sense").

**Fix (defense in depth — do all three):**

1. **Type the scope in the key** so membership is looked up in exactly one collection:
   `media/c/<chatId>/<uuid>.ext` vs `media/g/<groupId>/<uuid>.ext`. Update `KEY_FORMAT` +
   `MEDIA_KEY_FORMAT` together (they must stay byte-identical) and dispatch
   `callerMayAccessScope` on the discriminator — never "try both collections".
2. **Make the fall-through explicit and closed.** Until keys are typed, if `chats/{scopeId}`
   exists, decide on chats *only* and return `false` when the caller is not a participant —
   a chat ID must never be satisfiable by a group document.
3. **Constrain group IDs server-side.** Deny client `create` on `groups/{groupId}`
   (`firestore.rules:99`) and mint groups through an Admin-SDK endpoint that assigns a
   server-generated ID in a namespace disjoint from chat IDs, mirroring what `/createChat`
   already does for chats (F6). This also fixes the "deleted group can be resurrected by an
   ex-member" latch failure, which is independently worth Session 06's attention.

---

## S03-H2 (High) — One authenticated account can exhaust the Worker's global 90 K/day request budget → platform-wide media outage (the per-user limiter cannot stop it)

**Location:** `worker/src/index.js:224-240` (global gate), `:259-287` (per-user limiter),
`server/index.js:426` (`mediaToken: 120`/min), `:475` (10-minute TTL).

The Worker has exactly two throttles, and they interact badly:

```js
// worker/src/index.js:261-269 — bucket key = hash of the whole Authorization header
async function credentialBucketKey(request) {
  const auth = request.headers.get('Authorization') ?? 'anon';
  const hash = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(auth));
  ...
```

The comment above it (`:252-258`) still describes the **pre-SEC-A01** world — "The credential
hash is non-spoofable (only the authorized app knows the WORKER_SECRET) and stable within a
session." That is no longer true: the `Authorization` value is now a **capability token that is
unique per (key, op, expiry)**. So the "per-user" bucket is really **per-token**: every distinct
object/verb the client touches gets its own fresh 120-req/min allowance. The one thing the token
*does* carry — `holder` (the `uidTag` pseudonym, `server:496`, returned by `verifyMediaToken` as
`cap.holder`, `worker:193`) — is never used for throttling.

**Exploit path:** mint 120 tokens/min (the server's own limit, `server/index.js:426`), each valid
for 10 minutes (`:475`) and each reusable an unlimited number of times. That is up to ~1,200
concurrently-valid tokens, each with its own 120/min Worker bucket → on the order of 10⁴–10⁵
requests/minute from a single account. The global daily gate (90 K, `worker:9`) is consumed in
minutes, after which **every** user's media returns 429 until midnight UTC. The per-isolate Map
is also per-PoP (`:242-250`), so distributing the flood across edge locations multiplies the
ceiling further. GETs are the cheapest lever and also proxy R2/B2 egress.

Note the sampled counter (`:236`, 1-in-10 write, +10) makes the global figure approximate but not
bypassable in either direction — it is unbiased, so exhaustion still happens on schedule.

**Fix:**
- Rate-limit on `cap.holder`, not on the raw header: move the limiter to *after*
  `verifyMediaToken` and key the bucket on `holder` (already a stable per-user pseudonym).
  This is a two-line change and is the single highest-leverage fix in this session.
- Add an aggregate per-holder budget (requests **and** bytes) over a longer window than one
  minute, so token minting is not a way to buy fresh quota.
- Consider making the daily gate reject per-holder over-consumers first, instead of failing
  the whole tenant at once (fate-sharing is what turns abuse into an outage).

---

## S03-H3 (High) — No per-user storage quota: ~19 uploads from one account fill the entire 9.5 GB global R2 cap and stop uploads for every user

**Location:** `worker/src/index.js:6` (`MAX_R2_BYTES` = 9.5 GB, global), `:20`
(`maxFileSize` default **500 MB**), `:445-457` (the only size/quota checks), `:298-312`
(`adjustR2`).

The only storage checks are (a) per-file size and (b) a **global** byte cap. There is no
per-user, per-scope, or per-day byte accounting anywhere — the counter is a single KV key,
`global:storage:r2`. With the default `MAX_FILE_SIZE` of 524,288,000 bytes, **19 uploads**
(9.5 GB / 500 MB) exhaust the cap, and `/mediaToken` will happily authorize them all: they can
be uploaded into a conversation the attacker legitimately belongs to (no forgery needed at all),
or, via S03-H1, into someone else's. After that, every upload platform-wide returns
`507` (`:451-456`).

Recovery is not automatic in the way that matters:
- Objects only leave R2 after `HOT_TIER_DAYS` (30) via the nightly cron, so the outage persists
  for **weeks** unless an operator manually deletes objects.
- Migration moves the bytes to **B2, which has no enforced cap at all** — `MAX_B2_BYTES` is
  explicitly "informational use only, no hard cap enforced" (`:11-12`) and cold-tier objects have
  "no auto-expiry" (`:389`). So the attack converts into unbounded permanent storage growth
  (and then billing) rather than being shed.
- `adjustR2` is a non-atomic read-modify-write (`:300-302`) and only *logs* when the cap is
  exceeded (`:309-311`), so concurrent uploads overshoot the cap rather than being reserved
  against it.

Also note a token holder can **re-PUT an existing key** to reset `customMetadata.uploadedAt`
(`:463`), which resets the 30-day tiering clock and pins an object in R2 indefinitely.

**Fix:**
- Enforce a per-holder storage budget (bytes stored and bytes/day uploaded), keyed on
  `cap.holder`, checked in the PUT path before `HOT_BUCKET.put`.
- Lower `MAX_FILE_SIZE` to something a messenger actually needs (the migration path already
  assumes "≤ 10 MB" per file, `:632` — the 500 MB default contradicts it).
- Have `/mediaToken` refuse `write` tokens once the caller is over budget, so the expensive
  request never reaches the Worker.
- Give cold-tier objects a retention policy, or enforce `MAX_B2_BYTES` rather than only
  reporting it.

---

## S03-M1 (Medium) — Attacker-controlled `Content-Type` is stored and echoed back verbatim, with no `nosniff` and no extension/type agreement

**Location:** `worker/src/index.js:459-464` (store), `:490-498` (R2 serve), `:514-522` (B2 serve).

```js
const contentType = request.headers.get('Content-Type') || 'application/octet-stream';
await env.HOT_BUCKET.put(key, request.body, { httpMetadata: { contentType }, ... });
```

The key extension is tightly allow-listed to `jpg|mp4|m4a|3gp` (`:409`), but the **declared
content type is not validated against it** and is replayed on every GET, with `Cache-Control`
but **no `X-Content-Type-Options: nosniff` and no `Content-Disposition`**. A holder of any
`write` token can therefore store an object at `…/x.jpg` that is served as
`text/html`, `image/svg+xml`, or `application/javascript` with attacker-chosen bytes.

**Reachability:** the current consumer is the Android client, which fetches bytes and decrypts,
so this is **defense-in-depth today** — the impact lands the moment any browser, WebView, or
`ACTION_VIEW` intent opens a Worker URL directly, and the Worker already ships permissive CORS
(`:54-64`, `Access-Control-Allow-Origin: *`) explicitly anticipating a browser client. Combined
with S03-H1, the attacker can plant such an object under *another* conversation's prefix.
Session 08 owns the client-side half (does anything hand these URLs to a WebView?).

**Fix:** derive the served `Content-Type` from the key's allow-listed extension and ignore the
client header (or reject a mismatch at PUT); add `X-Content-Type-Options: nosniff` and
`Content-Disposition: attachment` to both the R2 and B2 response paths.

---

## S03-M2 (Medium) — Capability tokens are scope-bound but not uploader-bound: either chat participant can overwrite or permanently delete the other's media, bypassing the sender-only `deletedForAll` control

**Location:** `server/index.js:2005-2015` (scope is the only authorization input),
`worker/src/index.js:439-483` (PUT overwrites unconditionally), `:530-570` (DELETE);
contrast `firestore.rules:81-82` and `:86-92`.

The Firestore rules deliberately make destruction asymmetric: only the original sender may set
`deletedForAll` (`:81-82`, the F21 fix — "prevents recipient from silently erasing the sender's
messages on both devices"), and only the sender (or expiry) may delete a message document
(`:86-92`). The media layer does not mirror this. `/mediaToken` authorizes on **conversation
membership alone**; it records no notion of who uploaded an object, and the Worker's PUT does no
existence check and its DELETE does no ownership check. Any participant who knows a key — and
recipients always know the keys of media they received — can:

- `op: "delete"` → permanently destroy the sender's media in both tiers (`:537`, `:546-548`),
  leaving the message row intact and undecryptable, which is precisely the outcome F21 exists
  to prevent; and
- `op: "write"` → overwrite the ciphertext at that key with arbitrary bytes. Content stays
  confidential (AES-GCM fails closed on the client), so the realistic impact is **integrity
  destruction / denial**, not forgery.

Rated Medium: it needs no forgery and no S03-H1, but it is confined to conversations the
attacker legitimately belongs to, and the loss is availability of already-delivered media.

**Fix:** record the uploader when a `write` token is first used (or bind it into the token and
have the Worker persist it as `customMetadata.owner`), then require `holder == owner` for
`write`-over-existing and `delete`. At minimum, make PUT-over-an-existing-key an error so
overwrite is not a silent primitive, and treat cold-tier deletes as soft deletes.

---

## S03-M3 (Medium) — Minted tokens are unrevocable bearer credentials for 10 minutes, with unlimited reuse

**Location:** `server/index.js:475` (`MEDIA_TOKEN_TTL_MS`), `:495-500` (no nonce/jti),
`worker/src/index.js:146-194` (no replay or revocation check).

A token is a stateless bearer credential: no nonce, no single-use marker, no server-side
registry, and the Worker has no channel to ask whether it is still valid. Consequences:

- **Unlimited operations per token** for 10 minutes — the multiplier behind S03-H2.
- **Authorization outlives the fact it was based on.** Removal from a group, an
  `accountLock` latch (`firestore.rules:341`), a duress event, or a Firebase session revocation
  do not invalidate outstanding tokens; nor does `verifyIdToken` use `checkRevoked` on the
  minting path (`server:1965`, cross-ref **S02-I3**). A user who is locked out at second 0 can
  still delete media at second 599.
- Tokens travel in the `Authorization` header to a third-party edge; anything that observes one
  (a proxy, a crash log, an HTTP debug build) replays it for the remainder of the window.

Rated Medium because the blast radius per token is one object and one verb — the SEC-A01 design
working as intended — and 10 minutes is a defensible upload window.

**Fix:** shorten the TTL for `delete` specifically (seconds, not minutes); add a `jti` and have
the Worker mark single-use for `delete` (KV, TTL-bounded); check `accountLock` and use
`verifyIdToken(..., true)` on the mint path so a frozen account cannot obtain new tokens at all.

---

## S03-L1 (Low) — `WORKER_SECRET` is still compiled into every APK although the app no longer uses it

**Location:** `app/build.gradle:71-77`.

```gradle
def workerSecret = (localProps.getProperty('worker.secret', '') ?: '').trim()
if (workerSecret.isEmpty()) workerSecret = (System.getenv('WORKER_SECRET') ?: '').trim()
buildConfigField "String", "WORKER_SECRET", "\"${workerSecret}\""
```

`BuildConfig.WORKER_SECRET` has **no runtime references** in `app/src` (only SEC-A01 comments),
yet it is still baked into the binary, and the surrounding comment still claims "Every Worker
request (PUT/GET/DELETE/stats) must include: Authorization: Bearer <WORKER_SECRET>". If a release
is built with `worker.secret` set, every installed copy ships the operator credential for
`/stats` (`worker/src/index.js:362`), which discloses aggregate storage and request volumes —
useful reconnaissance for S03-H2/H3 (an attacker can watch the daily counter and the R2 cap in
real time and confirm the outage). `ARCHITECTURE.md:188` already flags this as "still in APK —
confirm blast radius": confirmed, blast radius is `/stats` read-only disclosure.

**Fix:** delete the `buildConfigField` and the stale comment; move `/stats` behind the admin
surface (Session 05) rather than a build-time constant; rotate `WORKER_SECRET` since any
already-published APK built with it must be assumed to have leaked it.

## S03-L2 (Low) — Unguarded `decodeURIComponent` on the request path throws before any handling

**Location:** `worker/src/index.js:402`

```js
const key = decodeURIComponent(url.pathname.slice(1));
```

A malformed percent-escape (`GET /media/x/%zz.jpg`, or a lone `%`) makes `decodeURIComponent`
throw `URIError` outside any `try`, so the Worker returns a generic 500/1101 instead of a 400 —
an unauthenticated request reaching an uncaught exception path. Low impact (no state, no
information), but it is the one input-handling step that precedes every control in the file.

**Fix:** wrap in `try/catch` and return `json({ error: 'Invalid file key' }, 400)`.

## S03-L3 (Low) — Dead B2 presign code path and rate-limit entries for endpoints that no longer exist

**Location:** `server/index.js:410-412` (`b2PresignedPut`/`b2PresignedGet`/`b2Delete` buckets),
`:2912-2928` (`b2PresignUrl`), `server/lib/pure.js:96-134` (`buildB2PresignUrl`).

There is no `/b2PresignedPut`, `/b2PresignedGet`, or `/b2Delete` route in `server/index.js`
(the comment at `:2912` names three endpoints that do not exist). The helper reads
`B2_KEY_ID`/`B2_APPLICATION_KEY` from the environment and would mint **bucket-wide** presigned
URLs, entirely outside the capability-token model, if any future route called it. Retained,
credential-touching, unreferenced signing code adjacent to a boundary that was just rewritten is
a footgun, and the stale comments actively mislead the next reviewer about which surfaces exist.

**Fix:** delete `b2PresignUrl`, `buildB2PresignUrl`, their tests, and the three
`AUTH_RATE_LIMITS` entries; if a presign path is ever needed, re-derive it inside the
capability-token model.

## S03-L4 (Low) — Rate-limit and quota rejections are served without CORS headers; browser clients see an opaque failure

**Location:** `worker/src/index.js:47-52` (`json()` sets no CORS), used by `:229-233`, `:277`,
`:403`, `:411`, `:427`, `:446`, `:451`, `:475`, `:525`, `:526`, `:561`, `:565`, `:569`, `:572` —
only the success paths (`:496`, `:520`) and the `/stats` 401 (`:366`) merge `corsHeaders()`.

Not exploitable against the Android client (CORS is browser-only, as the comment at `:55-57`
notes), but it means a future browser client cannot read *any* error — including the 429/507
signals from S03-H2/H3 — and will report every failure as a generic network error, which degrades
incident diagnosis. Recorded because the file explicitly anticipates a browser client.

**Fix:** have `json()` always include `corsHeaders()`.

---

## S03-I1 (Info) — The Worker holds bucket-wide B2 credentials (TB-8)

`getB2Client` (`worker/src/index.js:32-39`) uses `B2_ACCESS_KEY_ID`/`B2_SECRET_ACCESS_KEY` with
no object or prefix restriction, and the Worker also holds the R2 binding and
`MEDIA_TOKEN_SECRET`. A Worker compromise (or a stolen Cloudflare API token — Session 09) is
therefore total: read, overwrite, and delete of every user's media in both tiers, plus the
ability to mint its own capability tokens. Worth an explicit accepted-risk note; if Backblaze
application keys can be scoped to the bucket with restricted permissions, do that.

## S03-I2 (Info) — All Worker quota accounting is advisory, by documented design

The daily counter is 1-in-10 sampled (`:236`), `adjustR2` is a non-atomic read-modify-write
(`:300-302`), the rate limiter is per-isolate/per-PoP (`:242-250`), and KV write failures are
swallowed (`:211-215`). The comments are honest that Durable Objects would be needed for real
atomicity. This is the environmental reason S03-H2 and S03-H3 are as easy as they are: there is
no hard reservation anywhere in the media path. Any fix that depends on a *precise* counter will
not hold on the free tier — prefer bounds that fail safe when the counter is stale.

## S03-I3 (Info) — `/mediaToken` is a scope-membership oracle, and denials are logged per attempt

`:2007-2011` returns 403 with a distinct body for a scope the caller does not belong to, versus
200 for one they do, letting an attacker test membership in arbitrary chat/group IDs at 120/min.
This adds little beyond the enumeration trade-off already recorded in S01-I1/S02-I1 (and S03-H1
makes it moot for any scope the attacker can shadow), but it belongs in the same product
decision. The denial log line (`:2008`) records `uidTag` + raw `scopeId` — consistent with the
pseudonymisation policy, though `scopeId` is itself a conversation identifier.

---

## Handoffs

- **Session 04:** the Worker's limiters are the same best-effort pattern as the server's
  (S02-I2); fold S03-H2's holder-keyed fix into the durable-limiter decision.
- **Session 05:** move `/stats` behind the admin surface and out of the APK (S03-L1).
- **Session 06:** `groups/{id}` deletion + recreation defeats group-member removal (S03-H1,
  second half) — a latch-durability question that belongs with `accountLock`; also whether
  a locked account must be unable to mint media tokens (S03-M3).
- **Session 07/08:** whether any client surface hands a Worker URL to a WebView or external
  viewer (S03-M1), and whether the client's key-derivation makes object UUIDs guessable — the
  residual entropy that currently keeps S03-H1 out of Critical.
- **Session 09:** rotate `WORKER_SECRET`; scope the B2 application key (S03-I1); confirm no
  release build has `worker.secret` set in CI.
- **Session 10:** SEC-A01 is re-verified as *cryptographically* correct (see Verified-correct)
  but **not** as an authorization control until S03-H1 is fixed — record it as partially
  remediated, not resolved.

_End of Session 03. Next: Session 04 — Server egress & limits (`/linkPreview` SSRF,
`/turnCredentials`, rate limits, body/IP handling)._
