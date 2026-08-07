# Session 07 — Client Crypto & Key Management

_Audit session 07 of the DuoShield security review. Scope frozen at commit `378ce84` on branch
`v0/yetat43292-6054-24feaa1b`._

**Severity counts: 1 Critical / 3 High / 3 Medium / 4 Low / 3 Informational**

> ⚠️ **FIRST CRITICAL OF THE AUDIT — S07-C1.** Account authentication treats a **public** value as
> the sole credential. Any user holding one ordinary account can mint a Firebase session for any
> other account whose Account ID they know, without the seed phrase. See §4. This was in Session 02's
> scope (`/mintToken`) and was not caught there; §7 records the re-rating.

---

## 1. Scope

The on-device cryptography and everything that decides *which key* is used:

| Surface | Location |
|---|---|
| BIP39 → seed → identity key | `app/src/main/java/com/duoshield/app/crypto/SeedPhraseHelper.java` |
| Account ID derivation | `SeedPhraseHelper.java:546-564` (`deriveUserId`) |
| Seed → server session | `app/src/main/java/com/duoshield/app/auth/AuthTokenHelper.java` + `server/index.js:1436-1546` |
| Signal key generation / rotation | `crypto/signal/SignalKeyManager.java` |
| Signal store (trust decisions) | `crypto/signal/DuoShieldSignalStore.java` |
| X3DH / PQXDH session setup | `crypto/signal/SignalSessionManager.java` |
| Per-message encrypt/decrypt | `crypto/signal/SignalCipherHelper.java` |
| Group key generation + AEAD | `crypto/GroupCipherHelper.java` |
| Group key distribution | `ui/CreateGroupActivity.java:180-215`, `GroupChatActivity.java:334-392`, `:1266-1356` |
| Backup key derivation + AEAD | `crypto/BackupCryptoHelper.java` |
| Backup write/restore | `backup/BackupManager.java` |
| Key storage substrate | `util/SecurePrefs.java`, `db/DatabaseKeyProvider.java` |
| Safety-number surface | `KeyFingerprintActivity.java`, `ChatMediaActivity.java:2297-2320` |
| Relevant rules | `firestore.rules:7-29` (`users`/`public_keys`), `:98-155` (`groups`), `:282-315` (`backups`) |

**Threat model applied.** Per `README.md`, a compromised client can already read its own plaintext,
so findings here are scored by their effect on **other** users' confidentiality and on
guarantees the product advertises — key substitution, group-key handling, backup blob crypto,
and the integrity of "the server only ever sees ciphertext and metadata." One consequence of
scoring that way is that S07-C1 lands as a Critical even though it lives half in the server:
it is a Critical precisely *because* the client publishes the value the server accepts as proof.

**Inherited items carried in.** S01's cross-user prekey/one-time-key write gaps, S01's group-key
substitution concern, S03-H1 (`groups/{id}` self-asserted membership), and S06-I3 (does
`SecurePrefs` actually give hardware backing?). Each is resolved in §5.

---

## 2. Verdict

| Guarantee | Status |
|---|---|
| Identity key is derived from the seed with proper domain separation (HKDF, no raw `copyOf`) | ✅ **Correct** — `SeedPhraseHelper.java:487-524` |
| BIP39 PBKDF2 parameters match the standard (SHA-512, salt `"mnemonic"`, 2048, 64 B) | ✅ **Correct** — `:450-465` |
| Restore-from-seed reproduces the *same* identity key, so contacts see no safety-number change | ✅ **Correct** — deterministic derivation |
| TOFU trust store is never cleared or re-TOFU'd by app code | ✅ **Correct** — nothing outside libsignal writes `signal_trusted_id_*` |
| Signed pre-key / Kyber pre-key are signed by the identity key and verified by libsignal | ✅ **Correct** |
| Per-address session locking prevents ratchet clobbering | ✅ **Correct** — `SignalCipherHelper.java:60-77` |
| Group key is rotated when a member is removed | ✅ **Correct** — `GroupChatActivity.java:1311-1349` |
| Group key slot cannot be written by a non-creator | ✅ **Correct** — `firestore.rules:149-155` (closes S01's substitution concern) |
| **Possession of the seed is required to sign in** | ❌ **NO** — public key is the credential (S07-C1) |
| **The server only ever sees ciphertext and metadata for backups** | ❌ **NO** — unkeyed plaintext hash beside the blob (S07-H2) |
| **A group message's sender is cryptographically attributable** | ❌ **NO** — rules-only attribution, no AAD (S07-H3) |
| **Private key material is hardware-protected** | ❌ **NO** — silent plaintext fallback (S07-M1, resolves S06-I3) |

The primitives are, with one exception, chosen and used correctly — the HKDF domain separation,
the PBKDF2 parameters, the AES-GCM constructions, the per-address ratchet lock, and the
creator-only key slot are all genuinely right, and several are visibly the product of earlier
fixes. What fails is **what the cryptography is bound to**: an authentication check bound to a
public value, a group ciphertext bound to nothing, a backup blob shipped alongside an unkeyed
digest of its own plaintext, and a keystore that silently stops being a keystore.

---

## 3. Findings index

| ID | Severity | Title |
|---|---|---|
| S07-C1 | **Critical** | `/mintToken` authenticates accounts with a public value — takeover without the seed phrase |
| S07-H1 | High | `/mintToken` existing-account key check fails open when `identityPubKeyHash` is absent |
| S07-H2 | High | Backup docs ship an unkeyed SHA-256 of their own plaintext — offline plaintext-recovery oracle |
| S07-H3 | High | Group messages have no cryptographic sender binding — no AAD, attribution is rules-only |
| S07-M1 | Medium | `SecurePrefs` silently degrades to plaintext, and `isInitialized()` deliberately ignores it |
| S07-M2 | Medium | Identity trust is keyed on the mutable Firebase uid, so `/migrateUid` silently resets safety numbers |
| S07-M3 | Medium | Backup metadata sits outside the AEAD — `isDeleted` / `compressed` / missing `checksum` are server-controlled |
| S07-L1 | Low | `fetchGroupKey`'s creator check fails open when the cached `creatorUid` is null |
| S07-L2 | Low | Static `derivationCache` retains the identity key pair for the process lifetime, across a duress wipe |
| S07-L3 | Low | `mnemonicToSeed` does not canonicalize, `validateMnemonic` does — and `toLowerCase()` has no `Locale.ROOT` |
| S07-L4 | Low | `loadSession` silently substitutes a fresh session on deserialization failure |
| S07-I1 | Info | `SenderKeyStore` is a stub — Signal's group primitive is present but unused |
| S07-I2 | Info | No add-member flow exists; the rules already permit a key-less membership add |
| S07-I3 | Info | Account ID uses only 64 bits of `SHA-256(seed)` and is simultaneously the uid and the identity slot key |

---

## 4. Critical

### S07-C1 — `/mintToken` authenticates an existing account with a **public** value; any user who knows a victim's Account ID can mint a session for it without the seed phrase

**Severity: Critical** · **Locations:**
`app/src/main/java/com/duoshield/app/auth/AuthTokenHelper.java:101-120`,
`server/index.js:1471`, `server/index.js:1512-1518`,
`firestore.rules:16-17`,
`crypto/signal/SignalKeyManager.java:851-930` (bundle upload),
`SeedPhraseHelper.java:546-564` (Account ID = uid)

**Trust boundary broken:** TB-2 (client → push-server identity minting). The entire account model
rests on this boundary; every rule that says `request.auth.uid == …` inherits its authority from here.

#### What the code does

The client sends two fields and no proof:

```java
// AuthTokenHelper.java:114
body.put("userId",            userId);            // the shared Account ID, e.g. "K3MNP-Q8RXA-7BC"
body.put("identityPubKeyHex", pubKeyHex);         // hex of the identity *public* key
```

The server's existing-account branch is a hash comparison and nothing else:

```js
// server/index.js:1471
const incomingHash = sha256hex(identityPubKeyHex);
...
// server/index.js:1512-1518  (existing account)
} else {
  const storedHash = snap.data().identityPubKeyHash;
  if (storedHash && storedHash !== incomingHash) {
    throw Object.assign(new Error("Key mismatch"), { status: 403 });
  }
}
...
const token = await admin.auth().createCustomToken(userId);   // :1523
```

There is no nonce, no challenge, no signature, and no use of the identity **private** key
anywhere in the sign-in path. `AuthTokenHelper`'s own javadoc states the model plainly — *"The
server verifies `identityPubKeyHex` against the stored `identityPubKeyHash`"* — which is a
comparison of a hash of a value the protocol publishes on purpose.

#### Why both inputs are public

1. **`identityPubKeyHex`.** `SignalKeyManager.uploadPublicBundle()` writes the identity public key
   to `users/{uid}/public_keys/bundle.identityKey` — it *must* be published, because that is the
   key every peer needs for X3DH. The read rule is:

   ```
   // firestore.rules:16-17
   match /public_keys/{doc} {
     allow read:   if request.auth != null;
   ```

   Any authenticated user can read any other user's bundle. Base64-decode `identityKey`,
   hex-encode the 33 bytes, and you have `identityPubKeyHex` byte-for-byte — it is the same
   serialization `AuthTokenHelper.toHex()` produces from
   `IdentityKeyPair.getPublicKey().serialize()`.

2. **`userId`.** The Account ID is the seed-derived string users read out to each other to be
   added as a contact, and — because `createCustomToken(userId)` sets `uid = userId` — it is also
   the Firestore document id under `users/`. Its whole purpose is to be shared.

#### Exploit path

Attacker holds one ordinary account (explicitly in scope: *"Attackers may already hold one or
more authenticated accounts"*) and knows the target's Account ID (explicitly shareable):

```
1. GET  users/{victimAccountId}/public_keys/bundle        → allowed by firestore.rules:17
2.      hex(base64decode(doc.identityKey))                → identityPubKeyHex
3. POST /mintToken {"userId":"<victimAccountId>","identityPubKeyHex":"<hex>"}
       → 200 {"token": "<Firebase custom token for the victim's uid>"}
4.      FirebaseAuth.signInWithCustomToken(token)         → request.auth.uid == victim
```

No seed phrase. No device access. Two HTTP requests. The `waitlistRequestId` gate is on the
`!snap.exists` branch only, so it never applies to an existing account. The 60 s per-`userId`
cooldown and the 5-per-15-min per-IP limit (`server/index.js:390`) throttle the attack to one
token per minute — a token that is valid for a full Firebase session, so throttling is irrelevant.

#### Blast radius

The seed phrase still protects message **plaintext**: the attacker gets `request.auth.uid ==
victim` but not the identity private key, so existing ratchet sessions and the seed-derived
backup key stay closed. Everything the boundary was holding up, however, opens:

- **MITM for every future conversation.** With the victim's auth the attacker can `create`/`delete`
  and fully rewrite `users/{victim}/public_keys/bundle` (`firestore.rules:18,28` — owner-scoped
  writes). Substitute the attacker's `identityKey` + signed pre-key and every contact who has not
  yet established a session TOFUs the attacker (`DuoShieldSignalStore.isTrustedIdentity:164-167`
  returns `true` on first contact). Contacts who *have* a stored identity get the
  `safety_num_changed_` banner; new ones get nothing. This is the S01 "key substitution" concern
  reached from a completely different direction — and it does not need the S01 rule gaps at all.
- **Impersonation.** Send messages as the victim in 1:1 chats and in every group
  (`firestore.rules:133` binds `sender` to `request.auth.uid`, which is now the victim).
- **Denial of service / data destruction.** Delete the victim's bundle so nobody can start a
  session with them; overwrite `groups/{id}/keys/*` for groups the victim created.
- **Amplifies S06-H1.** `accountLock` is not consulted by `/mintToken` at all, so the duress lock
  does not slow this path down either; and the coerced-seed scenario S06 defends against is now
  moot, because the attacker never needed the seed.
- **Amplifies S07-H2.** Victim-authenticated reads of `backups/{uid}/messages/*`
  (`firestore.rules:291`) hand the attacker the ciphertext **and** the unkeyed plaintext digest,
  turning a server-operator-only oracle into an any-user oracle.

#### Fix

Require proof of possession of the identity **private** key. The client already has it, and
libsignal already exposes the primitive on both sides.

1. `POST /authChallenge {userId}` → server stores a single-use random 32-byte nonce with a short
   TTL (reuse the `_duressNonces` pattern from `server/index.js:2362-2408`, which Session 06
   verified as uid-bound, single-use, and atomically consumed).
2. Client signs a domain-separated transcript — e.g.
   `"DUOSHIELD_AUTH_V1" || userId || nonce || timestamp` — with
   `Curve.calculateSignature(identityKeyPair.getPrivateKey(), transcript)`.
3. `POST /mintToken {userId, identityPubKeyHex, nonce, signature}`; server verifies with
   `Curve.verifySignature` (or an equivalent XEdDSA verifier) against the **stored full public
   key**, consumes the nonce in the same transaction that currently claims the identity slot, and
   only then calls `createCustomToken`.
4. **Store `identityPubKey`, not just its hash,** in `identities/{userId}`. The hash exists to
   avoid storing the key; it cannot support verification, and keeping only a hash is what made the
   public value look like a secret in the first place. Also fixes S07-H1 for free.

Interim mitigation if the protocol change cannot ship immediately: this is not something a rate
limit can fix. The only real stopgap is to stop publishing the value that doubles as the
credential — which is impossible, because X3DH needs it. **Treat this as ship-blocking.**

---

## 5. High

### S07-H1 — the existing-account key check fails open when `identityPubKeyHash` is missing or empty

**Severity: High** · **Location:** `server/index.js:1514-1517`

```js
const storedHash = snap.data().identityPubKeyHash;
if (storedHash && storedHash !== incomingHash) {     // ← falsy storedHash ⇒ no check at all
  throw Object.assign(new Error("Key mismatch"), { status: 403 });
}
```

Any `identities/{userId}` document that exists but lacks a truthy `identityPubKeyHash` —
absent field, `""`, `null` — authenticates **any** key. The document is written in exactly one
place today (`:1506-1510`), always with the field, so this is not currently reachable by a
client; it is reachable by any operator action, Admin-SDK path, partial restore, or future
migration that touches `identities`. Combined with S07-C1 it is the same outcome by a shorter
route, and it survives S07-C1's fix unless corrected: a signature verified against a **missing**
stored key must also fail closed.

**Fix:** invert to a positive requirement — `if (!storedHash || storedHash !== incomingHash) throw
403`. After the S07-C1 fix, the same applies to the stored public key: no stored key ⇒ deny.

---

### S07-H2 — every backup document ships an unkeyed SHA-256 of its own plaintext, in cleartext, beside the ciphertext

**Severity: High** · **Locations:**
`crypto/BackupCryptoHelper.java:105-111` (`computeChecksum`),
`backup/BackupManager.java:102`, `:108-112`, `:1111-1130` (`toJson`),
also `:347-355`, `:511-519`, `:669-677`

**Trust boundary broken:** TB-1 / the product's core "zero-knowledge relay" claim.

Each backed-up message is written as:

```java
// BackupManager.java:101-112
String json     = toJson(msg);                                  // contains the PLAINTEXT body
String checksum = BackupCryptoHelper.computeChecksum(json);      // SHA-256(plaintext), unkeyed
String enc      = BackupCryptoHelper.encryptCompressed(key, json);
...
doc.put("enc",            enc);
doc.put("ts",             msg.getTimestamp());       // cleartext
doc.put("conversationId", msg.getConversationId());  // cleartext
doc.put("checksum",       checksum);                 // cleartext
```

`computeChecksum` is a bare, unsalted, unkeyed digest:

```java
// BackupCryptoHelper.java:105-111
MessageDigest md = MessageDigest.getInstance("SHA-256");
byte[] hash = md.digest(plaintext.getBytes(StandardCharsets.UTF_8));
```

**Why this recovers plaintext.** `toJson` (`:1111-1130`) is fully deterministic — fixed key order,
`org.json` output, `nvl()` normalizing null to `""`. Of its 16 fields, everything except the
message body is already known to whoever can read the backup document:

| Field | Known to the reader? | From where |
|---|---|---|
| `id` | ✅ | it *is* the backup document id (`:118`) |
| `conversationId`, `timestamp` | ✅ | cleartext fields on the same document (`:109-110`) |
| `sender`, `mediaUrl`, `mediaType`, `status`, `isDeleted`, `forwarded`, `edited` | ✅ | mirrored on the live message document under `chats/{id}/messages/{id}` |
| `starred`, `reaction`, `replyToId`, `replyPreview` | mostly ✅ / low entropy | booleans, emoji from a small set, sibling message ids |
| **`text`** | ❌ | **this is the secret** |
| `mediaKey` | ❌ | empty for text messages |

So the digest is effectively `SHA-256(fixed_known_prefix ‖ text ‖ fixed_known_suffix)` — a
verification oracle for `text` that is unkeyed, offline, and GPU-parallel at billions of
candidates per second. For the traffic that dominates a messenger — `"ok"`, `"yes"`, `"on my
way"`, a 6-digit code, a time, a name, a street address — the body is *recovered*, not merely
confirmed. Longer prose resists brute force but remains confirmable against any guess, which is
enough to answer "did they discuss X."

The reader is the Firestore operator, anyone with a database dump, and — via S07-C1 — any user
who knows the victim's Account ID.

**It is also redundant.** AES-256-GCM already authenticates the blob; its 128-bit tag is a
*stronger* integrity check than the digest, and it is verified before the digest ever runs
(`:220-227`). The checksum protects against nothing GCM does not already cover, and its stated
purpose — "corruption detected" — is exactly what a GCM tag failure reports.

**Fix:** delete the `checksum` field and `computeChecksum`/`verifyChecksum` entirely; let the GCM
tag be the integrity control, and treat `AEADBadTagException` as the corruption signal. Keep
reading the field for legacy documents only if you must, then stop writing it and migrate. If a
separate integrity value is genuinely wanted, it must be keyed — `HMAC-SHA256` under a key from a
distinct HKDF label (`"DUOSHIELD_BACKUP_MAC_V1"`, alongside the existing
`"DUOSHIELD_BACKUP_V1"` at `BackupCryptoHelper.java:45`) — never a bare digest of plaintext.

---

### S07-H3 — group messages carry no cryptographic sender binding; attribution is enforced only by a Firestore rule

**Severity: High** · **Locations:**
`crypto/GroupCipherHelper.java:43-58` (encrypt), `:65-79` (decrypt),
`GroupChatActivity.java:486`, `:564`,
`firestore.rules:130-134`,
`DuoShieldSignalStore.java:371-384` (the unused Signal primitive)

Group messaging uses one shared AES-256-GCM key for all members, with **no AAD**:

```java
// GroupCipherHelper.java:47-49
cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
        new GCMParameterSpec(TAG_BITS, iv));      // no cipher.updateAAD(...)
byte[] ct = cipher.doFinal(plaintext.getBytes("UTF-8"));
```

The ciphertext is therefore bound to the *group* but not to a sender, a message id, or a
timestamp. Any ciphertext that verifies under the group key verifies for every member equally.
The only thing that makes a message "from B" is a plaintext field on the Firestore document,
checked by a rule:

```
// firestore.rules:130-134
allow create: if request.auth != null
              && request.auth.uid in get(...).data.members
              && request.resource.data.sender == request.auth.uid
              && request.resource.data.isEncrypted == true;
```

That rule is correct and does block member-to-member forgery through the client SDK. But it is
the *whole* control, and the audit's own threat model places the server outside the trust
boundary. Anything that writes outside the rules engine can re-attribute at will:

- **the push server's Admin SDK** (`server/index.js` uses `admin.firestore()` throughout — Admin
  SDK writes bypass rules entirely),
- **a Firestore/console/service-account compromise**,
- **S07-C1**, which grants an attacker `request.auth.uid == someMember` and thus a rules-legal
  write with `sender` set to that member.

**Exploit path (Admin-SDK / compromised-server variant):**

```
1. Read groups/{g}/messages/{m1}  → {sender: "A", text: <ct>, isEncrypted: true}
2. Write groups/{g}/messages/{m2} → {sender: "B", text: <same ct>, timestamp: <chosen>}
3. Every member's GroupChatActivity.listenForMessages() (:430-490) decrypts <ct> under the
   group key, succeeds, and renders it as B's message at the chosen time.
```

No key material is needed — the attacker never learns the group key and never forges a
ciphertext. It replays an existing one under a new identity. Selective quoting ("B said *that*"),
timeline manipulation, and cross-message splicing all follow. 1:1 chats are immune because
Signal's Double Ratchet binds each message to the sender's identity key; groups are not, because
they do not use it.

**Fix (either, in preference order):**

1. **Use Signal's group primitive.** `DuoShieldSignalStore`'s `SenderKeyStore` is a live stub —
   `storeSenderKey` is a no-op and `loadSenderKey` returns `new SenderKeyRecord(new byte[0])`
   (`:371-384`). Sender Keys sign every group message with the sender's identity key, which is
   exactly the missing property, and the library is already a dependency.
2. **Bind AAD, as a smaller change.** `cipher.updateAAD((groupId + "|" + senderUid + "|" +
   msgId).getBytes(UTF_8))` on both sides, with the decrypt side taking `senderUid`/`msgId` from
   the Firestore document. A replayed ciphertext then fails its tag. Note this authenticates the
   *binding* only — it does not prove the sender holds a private key, so a member who legitimately
   received a message still cannot be distinguished cryptographically from the group at large.
   Prefer (1).

Track this together with S03-H1: both are "the group's identity is asserted in a document rather
than proven with a key."

---

## 6. Medium

### S07-M1 — `SecurePrefs` silently degrades to plaintext, and `isInitialized()` is written to ignore that

**Severity: Medium** (High if a forensic/root-level adversary is in scope — see note) ·
**Locations:** `util/SecurePrefs.java:170-174`, `crypto/signal/SignalKeyManager.java:261-278`,
`db/DatabaseKeyProvider.java:155-158`

**This resolves S06-I3.** Session 06 deferred the question of whether `SecurePrefs` is genuinely
hardware-backed, because duress-PIN deniability depends on it. The answer is **no**, on two counts:

1. Tiers 2 and 3 (`:135-168`) deliberately build a `KeyGenParameterSpec` with
   `setIsStrongBoxBacked(false)` and no `setUserAuthenticationRequired`, so the master key may be
   software-only — a documented and reasonable compatibility trade-off, but not hardware backing.
2. If all three tiers fail, the code falls through to **plaintext**:

   ```java
   // SecurePrefs.java:170-174
   // ── Fallback: plaintext (MODE_PRIVATE) ───────────────────────────────
   SharedPreferences sp = appCtx.getSharedPreferences(fileName, Context.MODE_PRIVATE);
   return new Built(sp, false);
   ```

`SignalKeyManager.isInitialized()` then documents, at length, that it must **not** gate on
`isAvailable()` (`:261-278`) — correctly, because gating it produced a sign-in loop. The
consequence is that the app proceeds to write, into a plaintext file, in one place:

- the seed-derived **identity private key** (`KEY_IDENTITY_KEY_PAIR`, `:798-799`),
- all one-time, signed, and Kyber pre-key private halves,
- the **backup AES key** (`BackupCryptoHelper.PREF_KEY`),
- the **SQLCipher passphrase** (`DatabaseKeyProvider.KEY_DB_CIPHER`) — which makes the encrypted
  message database openable,
- the app PIN and **duress PIN** hashes.

`DatabaseKeyProvider` at least logs a warning (`:155-158`); nothing surfaces anything to the
user, and `isAvailable()` has no caller that changes behaviour.

**Effect on other users.** Extracting the identity private key is not merely "the local user's own
plaintext." It permits permanent impersonation to every contact who has already TOFU'd that key —
with **no** safety-number change, because the key is unchanged — and decryption of that user's
existing ratchet sessions. That is a third party's confidentiality, which is why this is not an
Info.

Rated **Medium** here because reaching the file requires root or physical extraction, i.e. a
different adversary from the network attacker the other findings assume. Session 08 owns the
platform-hardening call (`allowBackup`, root/tamper posture, `minSdk 26` keystore reality) and
should re-rate with that context.

**Fix:** decide the policy explicitly rather than by fallthrough. At minimum: surface a
persistent, non-dismissable warning when `isAvailable() == false`; do not write the backup key or
enable cloud backup in that state; and treat "duress PIN available" as requiring
`isAvailable() == true`, since its guarantee is unsupportable otherwise. Consider a
`setUserAuthenticationRequired`-gated tier for the identity key specifically, so it is not
protected identically to a UI preference.

---

### S07-M2 — identity trust is keyed on the mutable Firebase uid, so a `/migrateUid` silently resets safety-number state

**Severity: Medium** · **Locations:** `DuoShieldSignalStore.java:56` (`KEY_TRUSTED_IDENTITY_PREFIX`),
`:114-147`, `:160-181`, `SignalSessionManager.java:107-108`,
`ui/RestoreFromSeedActivity.java:479-540` (`/migrateUid` call), `server/index.js` `/migrateUid`

Trusted identities are stored under `signal_trusted_id_<uid>.<deviceId>` and looked up by a
`SignalProtocolAddress` built from the **Firebase uid** (`SignalSessionManager.java:107`). The
identity key itself is stable across devices — it is seed-derived — but the uid is not: the
restore path calls `/migrateUid` precisely because a restore can land on a different uid.

When a contact's uid changes, the peer's device has `signal_trusted_id_<oldUid>.1` and no entry
for the new uid. `isTrustedIdentity` therefore takes the first-contact branch:

```java
// DuoShieldSignalStore.java:164-167
IdentityKey stored = getIdentity(address);
if (stored == null) {
    return true; // First contact — TOFU
}
```

A **silent re-TOFU**, with no `safety_num_changed_` flag and no banner — even though the app holds
the information needed to notice: the identity key it already trusts is byte-identical to the one
it is about to accept, so a key-keyed store would have matched and said nothing, while a *changed*
key under a new uid would have been caught. As written, both cases are indistinguishable and both
are silent.

Consequence: any path that gets a peer to talk to a new uid gets one free, unwarned TOFU. That
path is `/migrateUid` plus the contact record; Session 02 owns whether `/migrateUid` can be driven
by an attacker, and S07-C1 supplies a second way to rewrite whatever the peer resolves.

**Fix:** key the trust store on the stable seed-derived identity — `SignalKeyManager.getAccountId()`
(`:287-294`) already computes exactly this 66-hex-char value — or on the Account ID, and treat
`(uid → account)` as a lookup rather than the trust key. Migrate existing
`signal_trusted_id_<uid>` entries on upgrade. Until then, at minimum raise the safety-number
banner whenever a contact's uid changes, so a re-TOFU is never silent.

---

### S07-M3 — backup metadata sits outside the AEAD, so the server can suppress or break individual backup entries

**Severity: Medium** · **Locations:** `backup/BackupManager.java:108-112`, `:210-211`, `:216-227`,
`BackupCryptoHelper.java:120-122`

`enc` is authenticated by its GCM tag. The fields *beside* it are not, and the restore path
branches on all of them:

| Field | Restore behaviour | Server-controlled effect |
|---|---|---|
| `isDeleted: true` | `continue` before decrypting (`:210-211`) | **silently drop any message from the restore** |
| `compressed` flipped | wrong decrypt path chosen (`:220-224`) → exception → doc skipped (`:231-232`) | targeted restore failure |
| `checksum` altered | mismatch → `continue` (`:227-230`) | targeted restore failure |
| `checksum` **removed** | `verifyChecksum` returns `true` for a null/empty expected value (`BackupCryptoHelper.java:120`) | integrity check silently disabled |
| `ts` altered | changes `orderBy("ts")` paging (`:179`) | reorder/hide pages |

Confidentiality holds — none of this yields plaintext. Integrity and availability of the backup do
not: an adversary with write access to `backups/{uid}/messages/*` can excise chosen messages from
a restore, and the user sees a successful restore with a lower count. The rules do deny `delete`
(`firestore.rules:294`) specifically to force soft-delete, which makes `isDeleted` the intended
suppression mechanism — it is just not authenticated.

Note the last row also makes the S07-H2 checksum strictly worse than useless: it leaks plaintext
and can be switched off by removing it.

**Fix:** bind the metadata into the AEAD. Pass `uid|msgId|ts|conversationId|compressed` (and the
soft-delete flag) as GCM AAD, and derive the values used at restore from the decrypted JSON —
which already carries `id` and `conversationId` (`:1113-1114`) — rather than from the outer
document. Removing `checksum` per S07-H2 resolves two of the five rows outright.

---

## 7. Low

### S07-L1 — `fetchGroupKey`'s creator check fails open when the cached `creatorUid` is null

**Severity: Low** · **Location:** `GroupChatActivity.java:364-371`, `:281`

```java
if (creatorUid != null && !creatorUid.equals(sender)) {   // ← skipped entirely when null
```

`creatorUid` comes from the local Room copy of the group (`:281`, `g.createdBy`). If that column
is null — a group restored from a backup that predates the field, a partial sync, a group row
created before `createdBy` was populated — the check is skipped and the client decrypts and trusts
whatever key document it was served. The comment states the intent correctly (*"defense in depth
… guards against a stale/misconfigured rule or a future regression"*), which is exactly the
scenario in which a fail-open default removes the protection. Rated Low because
`firestore.rules:152-155` currently restricts that write to the creator, so this is the second
layer only.

**Fix:** fail closed — refuse when `creatorUid` is null, and re-fetch the group document to
resolve it before retrying.

### S07-L2 — the static derivation cache retains the identity key pair for the process lifetime, across a duress wipe

**Severity: Low** · **Location:** `SeedPhraseHelper.java:64-74`, `:500-503`, `:522`

`deriveIdentityKeyPair` memoizes the derived `IdentityKeyPair` in a static
`AtomicReference<CachedDerivation>`, keyed by `SHA-256(seed)`. It is never cleared. Two
consequences: the identity private key stays reachable in the heap for the whole process lifetime
(so a heap dump or `debuggable` build yields it without touching storage), and it survives
`DuressManager.performLogout()` / `WipeHelper.wipeAll()` until the process actually dies — a
duress wipe that does not kill the process leaves the key live in memory. `SHA-256(seed)` is also
retained; the seed has only 128 bits of entropy, so that value is a seed verifier, though
brute-forcing 2¹²⁸ is not the concern here.

**Fix:** add a `clearCache()` and call it from every wipe path; consider dropping the cache
entirely — it saves one 2048-iteration PBKDF2 plus an HKDF, which is not worth a process-lifetime
copy of the identity key.

### S07-L3 — `mnemonicToSeed` does not canonicalize, `validateMnemonic` does, and `toLowerCase()` has no `Locale.ROOT`

**Severity: Low** · **Locations:** `SeedPhraseHelper.java:400-429`, `:450-465`,
`ui/RestoreFromSeedActivity.java:139-150`

`validateMnemonic` lowercases each word and splits on `\s+` (`:402-408`). `mnemonicToSeed` does
neither — it only `trim()`s and NFKD-normalizes (`:452`). So `"Abandon  abandon …"` can validate
while deriving a *different* seed, hence a different identity key, Account ID, and backup key —
presenting as "restore succeeded but the account is empty."

The live caller is safe: `RestoreFromSeedActivity:139-148` canonicalizes to lowercase
single-spaced words before either call. The finding is the fragile contract — correctness depends
on every present and future caller repeating that normalization, with nothing enforcing it.

Second, narrower issue: both `validateMnemonic:408` and `RestoreFromSeedActivity:146` use
`toLowerCase()` with the default locale. In Turkish/Azeri locales `"I"` lowercases to `"ı"`
(U+0131), which is not in the wordlist, so a user whose keyboard capitalized a word containing
`I` cannot restore their account at all.

**Fix:** canonicalize inside `mnemonicToSeed` and `validateMnemonic` via one shared private
helper, and use `toLowerCase(Locale.ROOT)` everywhere.

### S07-L4 — `loadSession` silently substitutes a fresh session when deserialization fails

**Severity: Low** · **Location:** `DuoShieldSignalStore.java:297-311`

```java
} catch (Exception e) {
    Log.e(TAG, "Session deserialisation failed for " + key + " — returning fresh.", e);
    return new SessionRecord();
}
```

A corrupt row becomes an empty session rather than an error. `containsSession` still returns
`true` (it counts rows, `:315-318`), so `establishSession`'s fast path (`SignalSessionManager.java:117-121`)
reports "session already exists" and skips X3DH, and the next `encrypt` runs against empty state.
The ratchet resets without anything telling the user or the peer, and the peer sees decryption
failures it cannot explain. Rated Low because the input is local Room data, not attacker-supplied
— but it is exactly the state a partial-restore or a `BUG-D01` database recreation produces.

**Fix:** on deserialization failure, delete the row and let `containsSession` report false so the
session is renegotiated properly; surface it as a session reset rather than logging and continuing.

---

## 8. Informational

### S07-I1 — `SenderKeyStore` is a stub; Signal's group primitive is present but unused

`DuoShieldSignalStore.java:371-384` implements `storeSenderKey` as a no-op and returns
`new SenderKeyRecord(new byte[0])` from `loadSenderKey`, commented *"sender keys are only needed
for group messaging."* Group messaging exists (`GroupChatActivity`) and uses a hand-rolled shared
AES key instead. Recorded because it is the direct cause of S07-H3 and because the stub's
`new SenderKeyRecord(new byte[0])` would be an actively dangerous return value if anything ever
started calling it.

### S07-I2 — no add-member flow exists, and the rules already permit a key-less membership add

`GroupChatActivity` implements removal with rotation (`:1266-1356`); nothing implements addition.
`firestore.rules:109-118` lets the creator modify `members` freely, so a member can be added by a
raw document update with **no** entry written to `groups/{g}/keys/{newMember}` and no rotation. Two
properties a future add-member feature will inherit unless designed for:

- the new member has no key document, so `fetchGroupKey` shows *"Group key not found"* forever;
- if a key *is* distributed without rotation, the new member gains retroactive read access to the
  group's entire prior ciphertext (`firestore.rules:123-125` grants any current member read over
  all messages) — no backward secrecy.

Also note `removeMemberAndRotateKey` computes the remaining recipients from **local Room**
(`:1322`, `memberUids` from `getMemberUidsOf`) rather than the authoritative Firestore `members`
array, and skips any member whose Signal encrypt throws (`:1341-1343`) — a stale local copy or one
failed encrypt silently strands a member on the old key.

### S07-I3 — the Account ID uses 64 bits of `SHA-256(seed)` and is simultaneously the uid and the identity slot key

`deriveUserId` (`:546-564`) takes the first 8 bytes of `SHA-256(seed)` into 13 base-32 characters.
64 bits gives a ~2³² birthday bound. Because `createCustomToken(userId)` makes this string the
Firebase uid *and* the `identities/{userId}` document id, and `/mintToken`'s new-account branch is
first-claim-wins (`server/index.js:1485-1511`), a collision is an account collision: the second
user is permanently unable to register and receives a 403 key-mismatch. Not a concern at any
plausible user count; recorded because the value is load-bearing in three places at once and the
comment at `:539-541` describes 64 bits as *"the same collision resistance as the old hex format,"*
which invites future widening of its role.

---

## 9. Re-ratings and inherited items

| Item | Origin | Resolution in this session |
|---|---|---|
| **`/mintToken` authentication** | Session 02 scope | **Not caught in S02.** S02 reported 0C/1H and treated the F2 transactional identity claim as the security property. The claim *is* atomic — but the value it verifies is public (S07-C1). Session 02's `/mintToken` verdict must be reopened, and Session 10's final report should carry S07-C1 as the audit's first Critical. |
| S01 — group-key substitution | Session 01 | **Closed.** `firestore.rules:149-155` restricts `groups/{g}/keys/{member}` writes to the creator, and `GroupChatActivity.java:364-371` adds a client-side sender check (fail-open, → S07-L1). |
| S01 — cross-user prekey writes | Session 01 | **Confirmed correctly scoped.** `firestore.rules:22-27` limits cross-user `public_keys` updates to `['oneTimePreKeys','updatedAt']`, so a stranger consuming a prekey cannot touch `identityKey`/`signedPreKey`. The client side (`consumeOtpkOnFirestore`, `SignalSessionManager.java:340-380`) uses `arrayRemove` and retries; worst case is one prekey reuse, which is the documented degradation. **But** owner-scoped writes remain full-bundle rewrites (`:18`,`:28`) — which is what S07-C1 weaponizes. |
| S03-H1 — `groups/{id}` self-asserted membership | Session 03 | **Confirmed and extended.** The same "identity asserted in a document, not proven with a key" pattern is the root of S07-H3. Group them in Session 10. |
| S06-I3 — is `SecurePrefs` hardware-backed? | Session 06 | **Answered: no** (S07-M1). Tiers 2–3 are software-capable and the final fallback is plaintext. **S06's duress-PIN deniability claim is therefore unsupported** on any device that lands below tier 1, independent of the WorkManager residue in S06-H2. |
| S06-H1 — `accountLock` unenforced by `/mintToken` | Session 06 | **Reinforced.** S07-C1 shows `/mintToken` has no possession check at all, so adding a lock check there is necessary but not sufficient; both must be fixed in the same pass. |

---

## 10. Recommended fix order

1. **S07-C1** — challenge–response on `/mintToken`, and store the full public key. Ship-blocking; everything else in the account model depends on it.
2. **S07-H1** — one-line fail-closed change in the same handler; do it in the same commit.
3. **S07-H2** — stop writing `checksum`. A deletion, not a design change, and it closes a live plaintext-recovery oracle.
4. **S07-H3** — AAD binding as the tactical fix, Sender Keys as the real one.
5. **S06-H1 + accountLock enforcement** — belongs in the S07-C1 commit's blast radius.
6. **S07-M2** — re-key the trust store on the identity, before more installs accumulate uid-keyed entries to migrate.
7. **S07-M3, S07-M1, then the Lows.**

## 11. Session 08 handoff

Session 08 (client platform hardening) inherits:

- **S07-M1** — own the re-rating. `SecurePrefs`'s plaintext fallback is a platform decision:
  check `android:allowBackup`, `debuggable`, root/tamper detection, and what `minSdk 26` actually
  guarantees about keystore availability, then settle whether Medium or High is right.
- **S07-L2** — the in-heap identity key matters more or less depending on `debuggable`,
  `android:extractNativeLibs`, and whether ADB backup is reachable.
- **The SQLCipher passphrase** (`DatabaseKeyProvider.KEY_DB_CIPHER`) shares the store audited in
  S07-M1; the database's confidentiality is exactly the store's confidentiality.
- **Deep links / exported components** should be checked against `RestoreFromSeedActivity` and
  `KeyFingerprintActivity` specifically — both handle seed-derived material.
