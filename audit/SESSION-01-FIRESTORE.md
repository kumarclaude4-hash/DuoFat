# SESSION 01 — Firestore Authorization

**Scope:** `firestore.rules` (392 lines) + `firestore-tests/rules.test.js` (1,069 lines), read
together. TB-2 (Client ↔ Firestore) and, where relevant, TB-6 (server Admin-SDK writes that
land in the same collections).

**Method:** For every collection, walked `create / read / update / delete` against the threat
model (attacker holds ≥1 authenticated account, automates abuse, all client checks bypassable).
Then diffed the rules against the test suite to find behavior the tests do **not** pin down
(gaps by omission). Only server / rule enforcement is treated as a control.

**Result:** No unremediated **Critical**. **3 High**, **4 Medium**, **2 Low**, **2 Info**.
Second-pass additions (marked `[P2]`): S01-H3 (chat `partnerName` spoofing via unrestricted
chat doc update), S01-M4 (ex-member retains delete rights after removal on group messages).
The prior review's field-scoping fixes (F19/F21/F27/F28, legacy denials, one-way lock latch)
all hold. The findings are **value-level** and **field-completeness** gaps the existing tests
never exercise — they only assert *which* keys change, never *what values* land or whether
*content* and *display* fields are protected on update.

---

## Severity summary

| Severity | Count | IDs |
|---|---|---|
| Critical | 0 | — |
| High | 3 | S01-H1, S01-H2, S01-H3 `[P2]` |
| Medium | 4 | S01-M1, S01-M2, S01-M3, S01-M4 `[P2]` |
| Low | 2 | S01-L1, S01-L2 |
| Info | 2 | S01-I1, S01-I2 |

---

## S01-H1 — Cross-user prekey update lets any account wipe/replace another user's one-time prekeys (`firestore.rules:22-27`)

**Trust boundary:** TB-2. **Severity: High.**

The X3DH prekey-consume hole is scoped by `affectedKeys().hasOnly(['oneTimePreKeys','updatedAt'])`
(lines 25-26). `hasOnly` constrains *which* fields an update may touch — it says **nothing about
the values written**. A prekey "consume" and a prekey "destroy/replace" are byte-identical at the
rule layer.

```
allow update: if request.auth != null
              && ( request.auth.uid == uid
                   || request.resource.data.diff(resource.data).affectedKeys()
                        .hasOnly(['oneTimePreKeys', 'updatedAt']) );
```

**Exploit path (automated, any held account):**
1. Enumerate victims (`users/{uid}` is world-readable to authed clients — see S01-I1).
2. For each victim, `update users/{victim}/public_keys/bundle` with
   `{ oneTimePreKeys: [], updatedAt: <now> }`. Passes `hasOnly`.
3. Repeat on a loop / fan out across the directory.

Consequences, all without ever touching `identityKey`/`signedPreKey`:
- **Forward-secrecy downgrade (primary):** emptying `oneTimePreKeys` forces every new initiator to
  fall back to the signed prekey only (no one-time prekey in the X3DH handshake), removing the
  per-session forward secrecy the OTPK provides. This is exactly the BUG-F01/F05 failure the hole
  was meant to *prevent*, now reachable by an attacker instead of by accident.
- **Attacker-chosen prekey injection:** the attacker can instead write
  `{ oneTimePreKeys: [<attacker-generated prekey>], updatedAt: … }`. Any initiator who then
  consumes index-0 performs X3DH against an attacker key. On its own this does not break Signal's
  identity-key binding (the DH with the victim's long-term identity key still fails for the
  attacker), but it is a denial/confusion primitive and removes a defense-in-depth layer.
- **Directory-wide DoS:** looped across all discoverable users, this degrades new-session security
  for the entire user base from a single ordinary account.

**Why the tests miss it:** `rules.test.js:136-143` only asserts that a cross-user update of
`{oneTimePreKeys:[], updatedAt:2000}` **succeeds** — it treats the wipe as the desired behavior and
never distinguishes "consume one entry" from "destroy the list."

**Fix (defense-in-depth; the rule engine cannot fully police array semantics, so combine):**
- Constrain the operation to a *shrink*, not an arbitrary rewrite:
  `request.resource.data.oneTimePreKeys.size() == resource.data.oneTimePreKeys.size() - 1`
  so a cross-user update may only remove exactly one prekey, never zero the list or grow/replace it.
- Additionally require the surviving entries to be a subset of the prior list is not expressible in
  rules; move true prekey consumption **server-side** (a `/consumePrekey` Admin-SDK endpoint that
  pops atomically and rate-limits per caller), and change the rule back to owner-only writes. This
  is the robust fix and matches the "server is the only real control" model.

---

## S01-H2 — 1:1 message `update` does not protect content fields; a recipient can rewrite the sender's message body and flip `isEncrypted` (`firestore.rules:77-82`)

**Trust boundary:** TB-2. **Severity: High.**

`create` requires `sender == auth.uid && isEncrypted == true` (lines 71-72). The **`update`** rule
(lines 77-82) only enforces two things: `sender` is immutable, and only the original sender may set
`deletedForAll`. It does **not** restrict which other fields a participant may change, and it does
**not** re-assert `isEncrypted == true`.

```
allow update: if request.auth != null
              && request.auth.uid in get(.../chats/$(chatId)).data.participants
              && request.resource.data.sender == resource.data.sender
              && (!('deletedForAll' in request.resource.data.diff(resource.data).affectedKeys())
                  || resource.data.sender == request.auth.uid);
```

**Exploit path:** In chat `chat_ab` (`participants:[alice,bob]`), Bob (the *recipient* of Alice's
message `msg_1`, `sender:alice`) issues
`update chats/chat_ab/messages/msg_1 { ciphertext:<forged>, isEncrypted:false }`.
- `sender` is unchanged (`alice`), so the immutability check passes.
- `deletedForAll` is not in the diff, so that clause passes.
- Nothing else is checked → **allowed**.

Result: the persisted document still says `sender: alice` but now carries content Bob chose, and
`isEncrypted` can be `false`. Impact:
- **Attribution integrity break:** a message provably attributed to Alice (by the `sender` field the
  UI and any server logic trust) can be edited by the counterparty after the fact.
- **`isEncrypted` invariant defeated on update:** the create-time guarantee (`isEncrypted==true`,
  the F19/F28 protection) can be turned off later; any server/consumer that trusts `isEncrypted`
  to gate a decryption path can be fed a plaintext-flagged doc.

For 1:1 this primarily corrupts the *recipient's own* view (they can already read the plaintext),
so it is not a cross-user confidentiality break — but it is a genuine **integrity / non-repudiation**
failure of a security messenger and undermines an invariant the rest of the system relies on.

**Why the tests miss it:** the message-update path is **completely untested** — the
`/chats/{chatId}/messages` describe block (`rules.test.js:240-285`) covers only read, create, and
non-participant create. There is no update test at all, so this regression surface is invisible.

**Fix:** on `update`, whitelist the mutable fields and forbid content mutation. Minimum:
- re-assert `request.resource.data.isEncrypted == true`;
- forbid changes to `ciphertext`/`text`/`sigType`/`timestamp` by anyone
  (`!diff.affectedKeys().hasAny(['ciphertext','text','sigType','timestamp'])`), i.e. treat message
  bodies as append-only exactly as group messages already are (`firestore.rules:135` — group
  messages allow **no** update). Aligning 1:1 with the group rule (no body edits) is the cleanest fix.

---

## S01-M1 — Group message `create` allows a member to forge another member's `sender` is prevented, but a *removed* member window and unbounded doc content remain (`firestore.rules:130-134`)

**Trust boundary:** TB-2. **Severity: Medium.**

The `sender == auth.uid && isEncrypted == true` check (F28) holds. Two residual gaps:
1. **Membership is evaluated only at write time via `get()`** on the group doc. Because member
   removal (`/removeGroupMember`, `firestore.rules:109-118` creator-only `members` edit) and message
   writes are independent operations, a member who is *about to be* removed can keep writing until
   the members array is committed — a small race, not a durable hole, but note it as a TOCTOU on the
   `get()`-based membership check that recurs throughout the rules.
2. **No cap on message document count or size** — any member can bulk-write messages
   (cost/DoS against the group and the server's `collectionGroup("messages")` listener at
   `server/index.js:188`).

**Why the tests miss it:** group-message tests (`rules.test.js:360-395`) cover only read + a single
valid create; no removed-member or volume case.

**Fix:** accept the TOCTOU as inherent to rule `get()` (document it), and add abuse-resistance
server-side (per-UID write-rate accounting already exists for endpoints; extend to a Firestore
usage guard). No rule change strictly required, but the assumption should be explicit.

---

## S01-M2 — `identities` update allows the owner to mutate arbitrary non-key fields with no shape constraint (`firestore.rules:257-262`)

**Trust boundary:** TB-2 / TB-1 (identity binding). **Severity: Medium.**

`update` pins `uid` and `identityPubKeyHash` (good — this is the anti-takeover continuity check),
but places **no constraint on any other field** the owner writes into their own identity doc. Since
`identities/{userId}` is **readable by every authenticated user** (line 253, the contact-lookup
oracle), the owner can publish arbitrary attacker-controlled content into a globally-readable doc.

**Exploit path:** `asUser(victimId).update identities/{victimId} { fcmToken:…, label:'<payload>', … }`
— any string the client puts here is served to every other user performing a contact lookup. This is
a **stored-content injection / metadata-pollution** primitive into a world-readable collection; its
severity depends entirely on how consumers render/trust these fields (see S01-L2 for the client-render
concern). No integrity of the *key binding* is lost.

**Why the tests miss it:** `rules.test.js:589-593` asserts only that a benign `{label:'legacy'}`
update **succeeds**; it never probes malicious field content or unexpected keys.

**Fix:** restrict `update` to the exact allow-list of fields identity docs are supposed to carry
(`diff().affectedKeys().hasOnly([...])`) and validate types/sizes, so the world-readable doc cannot
be turned into an arbitrary broadcast surface.

---

## S01-M3 — `backup_logs` create is unbounded and unvalidated beyond `uid` (`firestore.rules:376-377`)

**Trust boundary:** TB-2. **Severity: Medium (cost/DoS).**

```
allow create: if request.auth != null && request.resource.data.uid == request.auth.uid;
```

Only ownership of the `uid` field is checked. There is no cap on document count, no size bound, and
no field-shape validation. An authenticated attacker can create unbounded `backup_logs/*` documents
(monotonic IDs) as themselves — a pure write-amplification / storage-cost DoS. Reads are denied so
there is no confidentiality impact, but Firestore billing and any downstream log processor are
exposed.

**Why the tests miss it:** `rules.test.js:851-887` checks the `uid` match and the read/delete
denials, never volume/shape.

**Fix:** validate a minimal schema (`hasOnly(['uid','event','ts'])`, `event` in an enum, `ts` a
number) and rely on server-side/quota controls for volume. This is defense-in-depth, not a boundary
break.

---

## S01-L1 — `groups` create does not validate `createdBy`; a creator can point it at another UID (`firestore.rules:99-100`)

**Trust boundary:** TB-2. **Severity: Low.**

`create` requires only `auth.uid in members`; `createdBy` is unconstrained. An attacker can create a
group with `createdBy: <someone else>`. This is **not** privilege escalation — the `keys/{memberUid}`
writer check is `auth.uid == createdBy` (line 152-154), so setting `createdBy` to a victim *locks the
attacker out* of key distribution rather than granting anything. It can, however, seed confusing
state (a group the "creator" never made) and interacts with any server logic that trusts `createdBy`.

**Fix:** require `request.resource.data.createdBy == request.auth.uid` on create.

---

## S01-L2 — `users` doc write has no field/shape validation; owner can store arbitrary content served to all authed users (`firestore.rules:9`)

**Trust boundary:** TB-2. **Severity: Low.**

`allow write: if request.auth.uid == uid` lets the owner put arbitrary fields/values in a
doc that **every** authenticated user can read (line 8). Same class as S01-M2 but on `users/{uid}`.
Impact is downstream-render-dependent (F11 notes the client renders server-supplied content
verbatim). Flagged as the collection-level enabler.

**Fix:** allow-list writable fields and bound their sizes/types.

---

## S01-I1 — Global read oracle on `users` and `identities` is confirmed; needs an explicit product decision (`firestore.rules:8`, `:253`)

**Trust boundary:** TB-2. **Severity: Info (accepted trade-off — must be ratified).**

Any authenticated account can read **any** `users/{uid}` and **any** `identities/{userId}` doc. This
is intentional (ECDH/X3DH key fetch, contact lookup, FCM token delivery) but is a full
**enumeration + metadata oracle**: given the deterministic UID format `XXXXX-XXXXX-XXX`, an attacker
can confirm which UIDs exist, harvest FCM tokens and display metadata, and pair this with S01-H1 to
target every user's prekeys. Recon §10 flagged it; Session 01's position: this is acceptable **only**
if (a) `users`/`identities` docs are minimized to non-sensitive fields (reinforced by S01-L2/M2
allow-listing) and (b) UID space is large enough that enumeration is infeasible. Record the decision
explicitly; do not leave it implicit.

**Fix / decision:** ratify as accepted with the field-minimization fixes above, or gate contact
lookup behind a server endpoint that rate-limits and avoids returning FCM tokens to arbitrary callers.

---

## S01-I2 — Pervasive `get()`-based cross-document authorization is a systemic TOCTOU + cost pattern (`firestore.rules:64-92, 122-138, 149-155, 167-211`)

**Trust boundary:** TB-2. **Severity: Info.**

Message, group-key, and call rules authorize by `get()`-ing a parent doc (chat participants, group
members/creator, call caller/callee). Two systemic notes: (1) every such `get()` is a billed read and
multiplies rule-evaluation cost under automated abuse; (2) authorization reflects the parent doc *at
evaluation time*, so any membership/participant change is subject to the ordering races noted in
S01-M1. Neither is a discrete vulnerability; both are properties an attacker can lean on and should be
acknowledged in the model rather than discovered later.

---

---

## S01-H3 `[P2]` — Chat doc update allows a participant to overwrite the partner's display name (`firestore.rules:52-60`)

**Trust boundary:** TB-2. **Severity: High.**

`/createChat` (`server/index.js:1905-1906`) writes two display-name fields onto the shared chat
doc:

```js
chatDocData["partnerName_" + partnerUid] = myDisplayName;   // the name BOB sees for Alice
chatDocData["partnerName_" + myUid]      = partnerDisplayName; // the name ALICE sees for Bob
```

The chat `update` rule (`:52-60`) blocks mutation of the *other* participant's `typing_`,
`online_`, `lastSeen_`, and `unread_` fields — but the `partnerName_<uid>` fields are **not
in the block-list**. Any participant can update them freely.

**Exploit path:**
1. Alice (attacker) is already in chat `chat_ab` with Bob.
2. Alice issues `update chats/chat_ab { "partnerName_alice": "<targeted string>" }`.
3. The update passes: Alice is a participant, `participants` is unchanged, and no blocked key
   is touched.
4. Bob's client now reads `partnerName_alice` as the display name it shows for Alice — changed
   to attacker-chosen content without Bob's knowledge and without re-triggering `/createChat`.

Unlike the S02-L2 issue (display names are stored at chat creation), this attack is **persistent
and repeatable on a live chat** — the attacker can continuously update the name shown to the
victim.

**Why the tests miss it:** no test exercises a `partnerName_*` field update at all. The
presence-spoofing tests (`rules.test.js:227-238`) only probe `lastMessage` updates and the
typed-out presence keys; `partnerName_*` is a blind spot.

**Fix:** add `partnerName_<otherUid>` to the blocked-keys list in the `allow update` rule, so
each participant may only update their own `partnerName_*` key (the name *they* want the partner
to see for them, i.e. their own display name), not the name attributed to the other party:

```
&& !request.resource.data.diff(resource.data).affectedKeys().hasAny([
     'partnerName_' + otherUid(resource.data.participants, request.auth.uid),
     'typing_'      + otherUid(...),
     ...
   ]);
```

---

## S01-M4 `[P2]` — Group message `delete` does not verify current membership; ex-members retain delete rights (`firestore.rules:137-138`)

**Trust boundary:** TB-2. **Severity: Medium.**

```
allow delete: if request.auth != null
              && resource.data.sender == request.auth.uid;
```

The rule checks only `sender == auth.uid` — there is **no membership check**. Compare the
`read` and `create` rules above it (`:122-134`) which both call `get(groups/$(groupId)).data.members`.

**Exploit path:** a member (Carol) is removed from group `group_1` (by the creator, updating
the `members` array). Carol no longer satisfies `auth.uid in members` and cannot read or write
new messages. But Carol can still issue `delete` on any message she previously sent — the rule
lets her through because `sender == carol` and membership is not re-checked on delete.

**Impact:** an ex-member can selectively expunge their own message history from a group after
departure — potentially erasing evidence, silently removing information others have acted on, or
destabilizing conversation continuity in an application where message integrity matters.

**Why the tests miss it:** the group-messages test block (`rules.test.js:360-395`) has no delete
test at all.

**Fix:** mirror the read/create guard on delete:

```
allow delete: if request.auth != null
              && resource.data.sender == request.auth.uid
              && request.auth.uid in
                 get(/databases/$(database)/documents/groups/$(groupId)).data.members;
```

---

## Verified-sound (no finding)

These were probed and hold under the threat model:

- **Chat `create` denied to clients** (`:44`) — server-only; cannot forge a chat to satisfy the calls
  gate. `participants` is always exactly 2 from `/createChat` (`server/index.js:1903`), so `otherUid`
  (`:34`) is well-defined and the F38 presence-spoofing block (`:52-60`) cannot be sidestepped via a
  malformed participants array (clients can't create/resize it).
- **Group-key substitution (F27)** — `keys/{memberUid}` writable only by `createdBy`; `createdBy`
  immutable on update; two-step escalation (rewrite `createdBy` then write key) fails at step 1.
  Tests `rules.test.js:428-461` confirm.
- **`deletedForAll` gating (F21)** — only original sender may set it (`:81-82`); recipient cannot
  erase the sender's copy. (Note: the *other* update fields are still open — see S01-H2.)
- **Account-lock one-way latch** — client may only ever create/update with `locked==true`
  (`:344-350`); delete denied (`:353`); cross-account write blocked by `auth.uid == accountId`. No
  cross-user lock DoS. Tests `rules.test.js:914-978` confirm.
- **Owner silos** — `recovery`, `backups/**`, `duressEligibility` (read-own, write-denied) all
  correctly owner-scoped; `backups` delete hard-denied (soft-delete model).
- **Server-only collections** — `_server_health`, `_duressNonces`, `waitlist`, `adminAuditLog`,
  legacy `rooms`/`conversations` all `if false` to clients; tests confirm.
- **Calls** ��� create gated on a pre-existing bilateral chat listing both parties; candidates + in-call
  chat restricted to the two participants. Sound given chat `create` is server-only.
- **`identities` create/update** — first-claim wins, `uid` self-bound, `identityPubKeyHash`
  continuity enforced; cannot retarget another account. (Field-shape gap is S01-M2, not a binding break.)

---

## Follow-ups handed to later sessions

- **Session 02 (server auth core):** S01-H1's robust fix is a server-side `/consumePrekey`; design it
  there. Confirm `/removeGroupMember` (`server/index.js:2309`) closes the S01-M1 removed-member window
  by rotating keys transactionally.
- **Session 07 (client crypto):** quantify S01-H1's real impact on the X3DH handshake (does the client
  hard-require an OTPK, or silently proceed signed-prekey-only?) and S01-H2's impact on any code that
  trusts `isEncrypted`/`sender` post-update.
- **Session 08 (client platform):** S01-L2/M2/I1 severity hinges on how `DuoShieldMessagingService`
  (F11) and contact-lookup UI render world-readable `users`/`identities` content.

---

_End of Session 01. Next: Session 02 — Server auth & identity (`/mintToken`, `/migrateUid`,
`/createChat`, `identities`)._
