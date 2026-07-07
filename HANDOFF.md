# DuoShield — Agent Handoff Document

> **Keep this current.** Update after every change. This is the authoritative
> source of truth for any agent (or human) picking up this work mid-stream.
> Spec file: `attached_assets/DuoShield_Revamp_Agent_Prompt_1781482894200.md`

---

## Project

Native Android (Java, minSdk 26, targetSdk 34) end-to-end encrypted 1-to-1 messenger.
- Package: `com.duoshield.app`
- Firebase project: `duoshield-8caf1`
- Room DB version: **12** (`MIGRATION_11_12` adds `contacts`, `groups`, `group_members` tables)
- Gradle 8.2.0, AGP 8.2.0 (requires JDK 17 to build)
- Signal library: `org.signal:libsignal-android:0.54.1`

---

## Implementation Phases

| # | Phase | Status |
|---|-------|--------|
| 0 | Reconcile current state — report findings | ✅ Done |
| 1a | `SeedPhraseHelper`, `SeedPhraseDisplayActivity`, `RestoreFromSeedActivity` | ✅ Done |
| 2 | `DuressManager.performLogout()` + 5-fail lockout rewrite | ✅ Done |
| 1b | Replace `SignInActivity`, delete `RecoveryHelper` | ✅ Done |
| 3 | Signal Steps 4/5 — `SessionCipher` send/receive swap | ✅ Done |
| 3.5 | Delete legacy crypto (`CryptoHelper`, `ECDHHelper`, etc.) | ✅ Done |
| 4 | Room migration — `messages.sigType` column (v9 → v10) | ✅ Done |
| §4 | Disappearing messages UI consolidation | ✅ Done |
| §5 | Media encryption (AES-256-GCM per-file key) + Unread launcher badge | ✅ Done |
| §6 | Signal prekey rotation — automatic pool replenishment when < 10 keys remain | ✅ Done |
| §7 | Signal signed pre-key rotation — weekly WorkManager job replaces medium-term SPK | ✅ Done |
| A | `FirebaseCostGuard` wired into all Firestore hot paths | ✅ Done |
| B | Disappearing-messages partner sync — write `disappear_ms` to Firestore, listen + Snackbar | ✅ Done |
| C | Group conversations — schema v12, `GroupCipherHelper`, `CreateGroupActivity`, `GroupChatActivity` | ✅ Done |

---

## §4 — Disappearing Messages Consolidation (COMPLETE)

### Problem that was fixed
`SettingsActivity` previously had **two competing self-destruct mechanisms**:
1. `switchSelfDestruct` + `editTtlMinutes` — a TTL-in-minutes toggle that set `self_destruct_minutes` and `self_destruct_enabled` in SharedPrefs. The `SelfDestructWorker` consumed these in a "Pass 2" that deleted messages by their send `timestamp`. This was **broken**: it deleted messages regardless of whether the receiving partner had also set a timer, and it worked on a schedule completely decoupled from `expiresAt`.
2. `btnDisappearing` — a duration picker that set `disappear_ms` in SharedPrefs and wrote `expiresAt = now + disappear_ms` onto every outgoing Firestore message doc. The `SelfDestructWorker` "Pass 1" correctly deleted by `expiresAt`. This was the correct mechanism.

### Changes made (2025-06-15)

#### `SettingsActivity.java` — rewritten
- **Removed**: `switchSelfDestruct`, `editTtlMinutes`, `layoutMinutes`, `textTtlHint` fields and all associated logic (`saveTtlAndSchedule()`, `cancelSelfDestruct()`, `setMinutesVisible()`, constants `DEFAULT_TTL_MIN`/`MIN_TTL`/`MAX_TTL`).
- **Removed**: direct `WorkManager`/`PeriodicWorkRequest`/`SelfDestructWorker` imports and calls.
- **Kept**: `btnDisappearing` / `showDisappearingPicker()` / `updateDisappearLabel()` — the only disappearing messages control.
- **Updated** `showDisappearingPicker()`: now delegates to `SelfDestructScheduler.schedule(ctx)` / `SelfDestructScheduler.cancel(ctx)` instead of inlining WorkManager calls.
- **Updated** `unpairDevice()`: calls `SelfDestructScheduler.cancel()` instead of direct WorkManager.

#### `activity_settings.xml` — layout updated
- **Removed** the entire "AUTO-DELETE" section (`switchSelfDestruct`, `layoutMinutes`, `editTtlMinutes`, `textTtlHint`).
- "DISAPPEARING MESSAGES" section (`btnDisappearing`, `tvDisappearSub`) is the only expiry control.

#### `SelfDestructWorker.java` — simplified
- **Removed** Pass 2 (`deleteTtlFromFirestore()`, `deleteOlderThan()` Room call, `self_destruct_minutes`/`self_destruct_enabled` pref reads).
- **Kept** Pass 1 only: `deleteExpired(now)` on Room + `deleteExpiredFromFirestore(conversationId, now)` on Firestore — both gate on `expiresAt > 0 && expiresAt < now`.
- `MessageDao.deleteOlderThan()` still exists in the DAO but has **no callers** — it is a dead method (safe to leave; removing it would require no migration).

#### `MessageBuilder.java` — wired `expiresAt`
- Now reads `disappear_ms` from `duoshield_prefs` and sets `expiresAt = now + disappear_ms` (or 0 if Off) on **both** the local Room row (`local.expiresAt = expiresAt`) and the Firestore doc (`doc.put("expiresAt", expiresAt)`).
- Previously `expiresAt` was omitted entirely from `MessageBuilder`, meaning messages sent via reply-receiver / forwarding were never cleaned up by `SelfDestructWorker`.

### Current disappearing messages flow (end-to-end)

```
User selects duration in Settings → SettingsActivity.showDisappearingPicker()
  → prefs.putLong("disappear_ms", ms)
  → SelfDestructScheduler.schedule(ctx)  [if ms > 0]
     → WorkManager enqueues SelfDestructWorker every 15 min

User sends text via ChatMediaActivity.sendMessage()
  → expiresAt = now + disappear_ms  (0 if Off)
  → Firestore doc.put("expiresAt", expiresAt)
  → Room local.expiresAt = expiresAt

User sends text via MessageBuilder.sendTextMessage()  [reply-receiver / forward]
  → same: reads disappear_ms, sets expiresAt on Room + Firestore

User sends media/voice via ChatMediaActivity
  → sendMediaMessage() / sendVoiceMessage() also set expiresAt  [already wired]

SelfDestructWorker.doWork()  [every 15 min]
  → Room: messageDao().deleteExpired(now)
  → Firestore: query expiresAt > 0 && expiresAt < now → batch delete
```

### SharedPrefs keys (disappearing messages)

| Key | Type | Meaning |
|-----|------|---------|
| `disappear_ms` | long | Duration in ms; 0 = Off. Options: 60k / 300k / 3.6M / 86.4M / 604.8M |

Keys `self_destruct_enabled` and `self_destruct_minutes` are **no longer written** by any code. Old installs may have these stale keys — they are harmlessly ignored.

---

## Architecture Rules (never violate)

- Room migrations must be explicit — never `fallbackToDestructiveMigration()`
- No Cloud Functions — all logic is client-side
- One Firestore listener per screen — attach `onStart()`, detach `onStop()`, null-guard before attaching
- `DiffUtil` for RecyclerView updates — never `notifyDataSetChanged()`
- No dual-version / cryptoVersion decrypt logic anywhere
- `DuressManager.performLogout()` = silent identity logout only (no wipe of messages or Firestore)
- `WipeHelper.wipeAll()` = full destructive wipe — only triggered by explicit "Wipe & Exit" menu item
- No `BiometricHelper` in `ChatMediaActivity.onCreate()` — lock handled by `BaseActivity.onStart()`
- The mnemonic must **never** appear in logs, Firestore, SharedPreferences, Room, or any file

---

## Signal Protocol State

### Key files

| File | Role |
|------|------|
| `crypto/signal/SignalKeyManager.java` | Key generation (seed-derived), SecurePrefs storage, Firestore bundle upload |
| `crypto/signal/SignalCipherHelper.java` | `encrypt()` / `decrypt()` via `SessionCipher`; per-partner lock map |
| `crypto/signal/SignalSessionManager.java` | X3DH `establishSession()` via `SessionBuilder.process(PreKeyBundle)` |
| `crypto/signal/DuoShieldSignalStore.java` | `SignalProtocolStore` impl — SecurePrefs + Room-backed |
| `crypto/signal/SeedPhraseHelper.java` | BIP39 mnemonic + PBKDF2 seed + Curve25519 key derivation |
| `models/SignalSessionRecord.java` | Room entity — PK = `"{uid}.{deviceId}"` |
| `db/SignalSessionDao.java` | `load`, `store`, `delete`, `count`, `getAddressesForName` |

### Room DB version 11

```
MIGRATION_8_9   → adds signal_sessions table
MIGRATION_9_10  → adds messages.sigType INTEGER NOT NULL DEFAULT 0
MIGRATION_10_11 → adds messages.mediaKey TEXT (nullable; Base64 AES-256 key per media file)
```

```
MIGRATION_11_12 → adds contacts, groups, group_members tables (see §C)
```

Next migration: v12 → v13. Write `MIGRATION_12_13` and add to `addMigrations()`. **Never use `fallbackToDestructiveMigration()`.**

### SecurePrefs keys (`duoshield_secure_prefs`)

| Key | Content |
|-----|---------|
| `signal_identity_key_pair` | Base64 of serialised `IdentityKeyPair` bytes (seed-derived) |
| `signal_registration_id` | int (stored as String) |
| `signal_signed_prekey` | Base64 of serialised `SignedPreKeyRecord` bytes |
| `signal_prekey_{id}` | Base64 of serialised `PreKeyRecord` bytes |
| `signal_prekey_ids` | Comma-separated list of current OTP prekey IDs |

### Firestore paths

```
/users/{uid}/public_keys/bundle    Signal public key bundle
/identities/{DS-XXXXXXXX}          DuoShield userId → Firebase UID + identity pub key hash
/chats/{chatId}/messages/{msgId}   Chat messages (sigType + expiresAt fields added)
```

---

## Firestore Message Doc Schema

```
id, conversationId, sender
text:        string (Signal ciphertext Base64)
sigType:     int    (1=SignalMessage, 3=PreKeySignalMessage)
type:        "text"|"image"|"video"|"voice"|"contact_card"
timestamp:   Timestamp (server)
status:      "pending"|"sent"|"delivered"|"read"
expiresAt:   long  (epoch ms; 0 = never)
edited:      boolean
replyToId, replyPreview: string
reaction:    string
path:        string  (B2 storage path for media/voice)
```

---

## Legacy Crypto — Deleted

The following files were deleted (Part 3.5). Do NOT recreate them:

- `crypto/CryptoHelper.java`
- `crypto/ECDHHelper.java`
- `crypto/CryptoInitializer.java`
- `crypto/KeyManager.java`
- `util/RecoveryHelper.java`
- `util/EncryptionHelper.java`
- `util/UploadHelper.java` ← deleted as dead code (zero callers; Part 3.6)
- `viewmodel/MessageViewModel.java`
- `viewmodel/ConversationViewModel.java`

`MediaHelper.java` and `VoiceNoteHelper.java` were rewritten to use `B2StorageHelper`. `ChatMediaActivity` and `MessageAdapter` call `B2StorageHelper` directly.

`PairingActivity.uploadMyEcPublicKey()` is a no-op (ECDH upload no longer needed).

---

## Part 3.6 — Compile-Fix: Remove all dead-class callers (COMPLETE)

All five remaining compile-breaking references to deleted classes have been resolved.

### `MainActivity.java`
- Removed `import com.duoshield.app.crypto.CryptoInitializer`
- Removed `CryptoInitializer.ensureKeyExists(this)` call (Signal keys init in `SeedPhraseDisplayActivity`/`RestoreFromSeedActivity`)
- Removed legacy `ecPublicKey` upload from the FCM token refresh block; now only uploads `fcmToken` via `Collections.singletonMap`

### `ConversationListActivity.java`
- Removed `import com.duoshield.app.util.EncryptionHelper`
- Replaced `EncryptionHelper.decrypt(ctx, last.toString())` with direct `last.toString()` — `ConversationMetaUpdater.update()` always writes a **plaintext** preview (≤80 chars) into `lastMessage`; no decryption needed

### `EditMessageHelper.java`
- Full rewrite: removed `EncryptionHelper.encrypt()` call
- New signature: `editMessage(Context ctx, String convId, String messageId, String partnerUid, String newText)`
- Re-encrypts on a background thread via `SignalCipherHelper.encrypt(ctx, partnerUid, newText)`; writes `text`, `sigType`, `edited=true` to Firestore
- **Dead code** (zero callers anywhere) — but must compile; correct Signal signature ready for future wiring

### `ChatMediaActivity.java` (two sites)
- **Line ~863** (`listenForMessages` — incoming message, `sigType == 0` branch):  
  Replaced the entire legacy-ECDH block (`CryptoInitializer.getSharedKey` + `CryptoHelper.decrypt`) with:  
  `displayText = "[Legacy message — not decryptable]"; shouldPersist = false;`
- **Line ~1498** (`retryPendingDecryption` — `sigType == 0` branch):  
  Replaced legacy-ECDH retry with a UI update to `"[Legacy message — not decryptable]"` + `resolved.add(id); continue;` so the message permanently leaves the retry queue
- Removed `import javax.crypto.SecretKey;` (no longer used anywhere in this file)

### `util/UploadHelper.java`
- **Deleted** — zero callers; the two `CryptoInitializer.getSharedKey()` calls it contained are gone with the file

### Remaining Javadoc references (harmless)
`SignalKeyManager.java`, `SecurePrefs.java`, and `ChatMediaActivity.java` all contain **comment-only** mentions of `CryptoInitializer`/`CryptoHelper` for historical context. These do not cause compile errors and can be cleaned up in a future lint pass.

### Dead method remaining
`ChatMediaActivity.anyFailedWithEcdhKey()` at line ~1534 — was last called from the now-removed legacy retry path. It has no callers but does not cause a compile error. Safe to delete in a future cleanup.

---

---

## §5 — Media Encryption + Unread Launcher Badge (COMPLETE, 2026-06-15)

### Media Encryption (AES-256-GCM per file)

All images, videos, and voice notes sent from this version onward are encrypted
before upload to B2 Storage. Legacy media (no `mediaKey` field) is served
as-is (null key → passthrough in `decryptAfterDownload`).

#### Design

| Piece | Detail |
|-------|--------|
| Algorithm | AES-256-GCM, random IV per file |
| Wire format | `[12-byte IV \| ciphertext \| 16-byte GCM auth tag]` |
| Key storage | Base64 (256-bit) in Firestore message doc field `mediaKey`; also in Room `messages.mediaKey` |
| Key generation | `KeyGenerator("AES", 256)` + `SecureRandom` per upload |

#### Files changed

- **`B2StorageHelper.java`** — removed passthrough `encryptBeforeUpload(byte[],SecretKey)` / `decryptAfterDownload(byte[],SecretKey)`; added `encryptForUpload(byte[])` → `EncryptedMedia {data, keyBase64}`, `decryptAfterDownload(byte[], String keyBase64)`, `loadMedia(String, String, MediaCallback)` (signature change: `SecretKey` → `String keyBase64`)
- **`Message.java`** — added `mediaKey TEXT` Room column + getter/setter
- **`AppDatabase.java`** — bumped to v11; added `MIGRATION_10_11`
- **`ChatMediaActivity.java`** — `uploadVoiceNote()` and `uploadMedia()` call `encryptForUpload()`, capture key; `sendVoiceMessage(path, key)` and `sendMediaMessage(path, type, key)` write `mediaKey` + `isEncrypted=true` to Firestore; `listenForMessages` reads `mediaKey` from doc and sets on Message; `onVoicePlay` passes `msg.getMediaKey()` to `loadMedia()`
- **`MessageAdapter.java`** — passes `msg.getMediaKey()` (videos) and `msg.getMediaKey()` (images) to `loadMedia()`
- **`MediaHelper.java`** / **`VoiceNoteHelper.java`** — updated to new API (no longer call removed methods)

### Unread Launcher Badge

Android 8+ launchers show a badge dot automatically from active notifications.
For launchers that support a count (Samsung, MIUI, etc.), `setNumber()` on the
notification sets the visible number.

#### Implementation

- **`NotificationHelper.showNotification()`** — increments `badge_count` in `duoshield_prefs` and passes it to `NotificationStyler.showMessage()`
- **`NotificationStyler.showMessage()`** — added `int badgeCount` parameter; builder calls `.setNumber(badgeCount)`
- **`ChatMediaActivity.clearBadge()`** — already existed; cancels all notifications + resets `badge_count=0` (called from `onResume()`)
- **`MarkReadReceiver`** — now also resets `badge_count=0` + `cancelAll()` when user taps "Mark Read" in the notification

#### SharedPrefs key

| Key | Type | Meaning |
|-----|------|---------|
| `badge_count` | int | Running unread count; 0 when chat is open or "Mark Read" tapped |

---

---

## §6 — Signal Prekey Rotation (COMPLETE, 2026-06-15)

Automatic one-time pre-key replenishment so the X3DH handshake never stalls from
an empty pool.

### Trigger chain

```
inbound PreKeySignalMessage received
  → SessionCipher.decrypt()
    → DuoShieldSignalStore.removePreKey(id)
      → SignalKeyManager.consumePreKey(ctx, id)   ← existing call
        → SignalPreKeyRefresher.checkAndReplenish(ctx)  ← NEW
```

### `SignalPreKeyRefresher` (new file)

| Symbol | Value | Meaning |
|--------|-------|---------|
| `THRESHOLD` | 10 | Trigger replenishment when fewer than this many keys remain locally |
| `BATCH_SIZE` | 25 | Keys generated and uploaded per replenishment cycle |
| `sRunning` | `AtomicBoolean` | Prevents concurrent replenishment runs |

Replenishment steps:
1. Guard with `AtomicBoolean` — no-op if already running.
2. Generate `BATCH_SIZE` Curve25519 key pairs with monotonically increasing IDs
   (read from `signal_prekey_next_id`).
3. Store private bytes in SecurePrefs via `SignalKeyManager.storeNewPreKeys()`.
4. Upload public bytes to Firestore `/users/{uid}/public_keys/bundle` using
   `FieldValue.arrayUnion()` — non-destructive, existing keys are preserved.

### `SignalKeyManager` additions

| New method / constant | Purpose |
|----------------------|---------|
| `KEY_PREKEY_NEXT_ID` | SecurePrefs key storing the next unused prekey ID (decimal string) |
| `getPreKeyCount(ctx)` | Counts IDs in the `signal_prekey_ids` CSV |
| `getAndIncrementNextPreKeyId(ctx, count)` | Atomic read-and-advance of the ID counter (wraps at 0xFFFFFF per Signal spec) |
| `storeNewPreKeys(ctx, List<PreKeyRecord>)` | Persists private key bytes + appends IDs to CSV |

`generate()` now also seeds `signal_prekey_next_id = 51` (= initial batch end + 1) so
replenishment IDs never collide with the initial batch of 50.

### Legacy devices (pre-§6)

Devices that upgraded from an older build will have no `signal_prekey_next_id` in
SecurePrefs. `getAndIncrementNextPreKeyId()` falls back to `51` in that case —
same safe starting point, no migration needed.

---

## §7 — Signal Signed Pre-Key Rotation (COMPLETE, 2026-06-15)

Weekly automatic rotation of the medium-term signed pre-key (SPK) used in every X3DH bundle.

### Why rotate the signed pre-key?

The SPK is a Curve25519 key pair signed by the identity key. It is included in the Firestore bundle that new session initiators fetch. If the private half were ever extracted from SecurePrefs, an attacker could retroactively compute session keys for all sessions established during its lifetime. Weekly rotation limits that window to 7 days.

### Trigger chain

```
DuoShieldApp.onCreate()
  → SignedPreKeyScheduler.schedule(ctx)
    → WorkManager.enqueueUniquePeriodicWork("SignalSignedPreKeyRotation", KEEP, 1-day interval)
      → SignedPreKeyRotationWorker.doWork()   [fires daily; only rotates if key ≥ 7 days old]
        → SignalKeyManager.rotateSignedPreKey(ctx)
```

### Grace period: "prev" SPK

A 1-to-1 message uses the SPK from the bundle that was live at session-establishment time. If Alice establishes a session against Bob's old SPK, then Bob rotates before Alice's first message arrives, Bob must still be able to decrypt using the old key.

**Solution**: `rotateSignedPreKey()` promotes the current SPK to `signal_signed_prekey_prev` before overwriting it. `DuoShieldSignalStore.loadSignedPreKey(id)` and `containsSignedPreKey(id)` both now check current → prev in order. The previous key lives for exactly one rotation cycle (7 days), after which it is overwritten on the next rotation.

### Files changed / created

| File | Change |
|------|--------|
| `crypto/signal/SignedPreKeyRotationWorker.java` | **New** — Worker; age-checks current SPK, calls `rotateSignedPreKey()` if ≥ 7 days old |
| `crypto/signal/SignedPreKeyScheduler.java` | **New** — schedules the Worker once per day with `KEEP` policy |
| `crypto/signal/SignalKeyManager.java` | Added `KEY_SIGNED_PREKEY_PREV`, `KEY_SIGNED_PREKEY_NEXT_ID`; seeded counter in `generate()`; added `getPrevSignedPreKey()`, `rotateSignedPreKey()`, `getAndIncrementNextSignedPreKeyId()`, `uploadRotatedSignedPreKey()` |
| `crypto/signal/DuoShieldSignalStore.java` | `loadSignedPreKey()` and `containsSignedPreKey()` now fall back to prev SPK |
| `DuoShieldApp.java` | Calls `SignedPreKeyScheduler.schedule(this)` in `onCreate()` |

### New SecurePrefs keys

| Key | Type | Meaning |
|-----|------|---------|
| `signal_signed_prekey_prev` | String (Base64) | Serialised previous SPK — grace period for in-flight sessions |
| `signal_signed_prekey_next_id` | String (decimal) | Next SPK ID to use; starts at 2 (initial key uses ID=1) |

### Legacy devices (pre-§7)

Devices upgrading from an older build have no `signal_signed_prekey_next_id`. `getAndIncrementNextSignedPreKeyId()` falls back to `SIGNED_PREKEY_ID + 1 = 2`. No Room migration required.

### Firestore update

`uploadRotatedSignedPreKey()` calls `bundle.update("signedPreKey", spkMap, "updatedAt", serverTimestamp())` — only the `signedPreKey` field is replaced; `identityKey`, `registrationId`, and `oneTimePreKeys` are untouched.

---

## §8 — Safety Number Banner + Bug Hunt (COMPLETE, 2026-06-15)

### Safety Number Banner

Surfaces a yellow warning strip in `ChatMediaActivity` whenever the partner's Signal identity key changes mid-session (e.g. partner re-installed, factory reset). Previously the change was silently accepted (TOFU) with only a logcat warning.

#### Trigger

`DuoShieldSignalStore.saveIdentity()` — when `existing != null && !existing.equals(incoming)`:
- Writes `safety_num_changed_<partnerUid> = true` to `duoshield_prefs`.
- Also refreshes `signal_partner_identity_key` in SecurePrefs so `KeyFingerprintActivity` shows the new (changed) key fingerprint.

#### UI

- `@id/safetyNumberBanner` — `LinearLayout` added to `activity_chat_media.xml` between the disappear-timer banner and the RecyclerView; `visibility="gone"` by default.
- "VERIFY" button → clears flag permanently + launches `KeyFingerprintActivity`.
- "✕" dismiss → hides for this session only; banner reappears next launch until user verifies.

#### Wiring in `ChatMediaActivity`

| Call site | When |
|-----------|------|
| `setupChat()` (end) | Initial page load |
| `onResume()` | After unlock / after returning from any overlay |
| `ensureSignalSession().onEstablished()` runOnUiThread | Immediately after X3DH completes (most likely trigger) |

### Bug Hunt Findings

| # | File | Bug | Fix |
|---|------|-----|-----|
| B-1 | `BaseActivity.java` | `lockScreenActive` static field referenced in `LockScreenActivity.unlock()` but never declared — compile error | Added `public static boolean lockScreenActive = false;`; also added `lockScreenActive = true` guard in `onStart()` to prevent stacking two `LockScreenActivity` instances |
| B-2 | `MessageReplyReceiver.java` | `showOpenAppNotification()` was dead code — never called; inline reply silently dropped when Signal keys not loaded | Now checks `SignalKeyManager.isInitialized(ctx)` and calls `showOpenAppNotification()` if false; also changed `partnerUid` default from `""` to `null` |
| B-3 | `ChatMediaActivity.java` · `EditMessageHelper.java` | Edited messages invisible to recipient and stale after restart — MODIFIED handler only processed `reaction`/`status`; Room never updated on edit | MODIFIED handler now re-decrypts partner-edited messages on `dbExecutor`; `EditMessageHelper.editMessage()` calls `messageDao().updateText()` on Firestore success; `MessageDao.updateText()` DAO method added |
| B-4 | `SessionLogActivity`, `SeedPhraseDisplayActivity`, `RestoreFromSeedActivity` | Three sensitive screens extended `AppCompatActivity` — bypassed the 3-minute app lock entirely. Seed phrase screen (most sensitive) was completely unprotected | Changed all three to `extends BaseActivity`; removed unused `AppCompatActivity` import; added `BaseActivity` import |
| B-5 | `ConversationListActivity.java` | `partnerUid` default `""` (empty string) instead of `null` — could pass empty string to Firestore sub-doc key (e.g. `"typing_"` with no UID) | Changed `getString("partner_uid", "")` → `getString("partner_uid", null)`; all guards already use `!= null` |
| B-6 | `BaseActivity.java` + 5 pre-auth screens | FLAG_SECURE absent — app thumbnail visible in recent-apps, screen recording possible | Added `getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE)` to `BaseActivity.onCreate()` (covers all 9 subclasses) + individually to `LockScreenActivity`, `SignInActivity`, `MainActivity`, `RestoreFromSeedActivity`, `SessionLogActivity`, `SeedPhraseDisplayActivity` |

### Files changed

| File | Change |
|------|--------|
| `crypto/signal/DuoShieldSignalStore.java` | `saveIdentity()` writes `safety_num_changed_<uid>` flag and refreshes `signal_partner_identity_key` |
| `res/layout/activity_chat_media.xml` | Added `@id/safetyNumberBanner` LinearLayout |
| `ChatMediaActivity.java` | `safetyNumberBanner` field; `checkSafetyNumberBanner()` method; wired in `setupChat()`, `onResume()`, `onEstablished()`; MODIFIED handler extended with B-3 edit re-decrypt block |
| `BaseActivity.java` | `lockScreenActive` static field + `onStart()` guard |
| `notifications/MessageReplyReceiver.java` | `showOpenAppNotification()` wired on key-not-loaded path; `partnerUid` default null |
| `db/MessageDao.java` | Added `updateText(String messageId, String text)` DAO method |
| `util/EditMessageHelper.java` | `addOnSuccessListener` after Firestore update calls `messageDao().updateText()` to sync Room cache |
| `ConversationListActivity.java` | `partnerUid` default `""` → `null`; `!isEmpty()` guard → `!= null` |
| `ui/SessionLogActivity.java` | `extends AppCompatActivity` → `extends BaseActivity`; import updated |
| `ui/SeedPhraseDisplayActivity.java` | `extends AppCompatActivity` → `extends BaseActivity`; import updated |
| `ui/RestoreFromSeedActivity.java` | `extends AppCompatActivity` → `extends BaseActivity`; import updated |

### P-1 — MessageAdapter targeted notifications (2026-06-15)

**Problem:** `appendMessage()` and `updateMessage()` both called `rebuildDisplay()` + `notifyDataSetChanged()` — a full O(n) rebind of the entire RecyclerView on every incoming message, every status tick, and every reaction update.

**Fix (`ui/MessageAdapter.java`):**
- `appendMessage()` — records `oldSize` before `rebuildDisplay()`, then calls `notifyItemRangeInserted(oldSize, inserted)`. Correctly handles 1 inserted item (message only) or 2 (date header + message).
- `updateMessage()` — applies the mutation to the `Message` object in `messages` (which `displayItems` already references via pointer), skips `rebuildDisplay()` entirely, then calls `notifyItemChanged(positionInDisplayItems)`. Falls back to `notifyDataSetChanged()` only if the message is somehow absent from `displayItems` (should never happen).
- `removeMessage()` — keeps `notifyDataSetChanged()` after rebuild; delete is a rare user action, and computing removed-header positions correctly would add complexity for negligible gain.

**Why it is safe:** `displayItems` stores the same `Message` object references as `messages`. Mutations made through `mutator.accept(msg)` are instantly visible in `displayItems` without any list rebuild.

---

### P-2 — MessageAdapter voice-playback targeted notifications (2026-06-15)

**Problem:** `setPlayingMessageId()` called `notifyDataSetChanged()` — a full O(n) rebind of every bubble just to toggle the play/pause icon on one voice note.

**Fix (`ui/MessageAdapter.java`):**
- Added private helper `notifyMsgById(String msgId)` that walks `displayItems`, finds the entry whose `Message.getId()` matches, and calls `notifyItemChanged(j)` for that position only. Silently no-ops if the id is null or not found.
- `setPlayingMessageId(msgId)` now captures the old id first, updates `playingMsgId`, then calls `notifyMsgById(oldId)` (reverts old item's icon from pause → play) and `notifyMsgById(msgId)` (sets new item's icon to pause). At most 2 items are rebound per invocation.
- `updatePinnedIds()` retains `notifyDataSetChanged()` — pin status affects every visible bubble's pin-indicator row, so a full rebind is correct there.

---

### Bug hunt coverage — complete (2026-06-15)

All Java source files reviewed across two sessions. Files checked clean with no further action:

**Crypto / Signal:** `SignalCipherHelper` · `SignalSessionManager` · `SignalKeyManager` · `SignalPreKeyRefresher` · `DuoShieldSignalStore` · `SignedPreKeyRotationWorker` · `SignedPreKeyScheduler` · `SeedPhraseHelper`

**Security / Auth:** `BiometricHelper` · `DuressManager` · `AppLockManager` · `LockScreenActivity` · `SignInActivity` · `SeedPhraseDisplayActivity` · `RestoreFromSeedActivity`

**Activities:** `ChatMediaActivity` (after all fixes) · `ConversationListActivity` (after fix) · `PairingActivity` · `KeyFingerprintActivity` · `FakeChatsActivity` · `FullScreenImageActivity` · `MediaViewerActivity` · `MessageSearchActivity` · `SessionLogActivity` · `SettingsActivity` · `MainActivity`

**Adapters / UI:** `MessageAdapter` (after P-1 + P-2 fixes) · `ConversationAdapter` · `SearchResultsAdapter` (uses DiffUtil ✓) · `SwipeToDeleteCallback` · `SwipeToReplyCallback` · `WaveformView`

**Notifications:** `NotificationHelper` · `NotificationStyler` · `DuoShieldMessagingService` · `MarkReadReceiver` · `MessageReplyReceiver` (B-2 fix)

**Firebase / Storage:** `MediaHelper` · `VoiceNoteHelper` · `B2StorageHelper`

**Database / Workers:** `AppDatabase` · `MessageDao` (after fix) · `SelfDestructWorker` · `SelfDestructScheduler` · `StorageCleanupWorker` · `TempFileCleaner`

**Utilities (all clean):** `ConversationMetaUpdater` · `MessageBuilder` · `EditMessageHelper` (after fix) · `ForwardMessageHelper` · `ReactMessageHelper` · `DeliveryReceiptHelper` · `ReadReceiptHelper` · `PinMessageHelper` · `MuteHelper` · `UnreadCountHelper` · `FcmTokenHelper` · `SessionLogger` · `WipeHelper` · `OnlinePresenceHelper` · `SearchHelper` · `SecureShareHelper` · `ExportHelper` · `VoiceMessagePlayer` · `VoiceRecorderHelper` · `PresenceThrottle` · `TypingThrottle` · `NetworkStateHelper` · `AppUpdateHelper` · `MediaSizeEstimator` · `GlideHelper` · `ImageCacheHelper` · `LastSeenFormatter` · `TimeFormatter` · `DateHeaderHelper` · `TextStyleHelper` · `HapticHelper` · `KeyboardHelper` · `ClipboardHelper` (auto-clears after 90 s ✓) · `FirebaseCostGuard` · `FirebaseQuotaSummary` · `PairingManager`

**Models:** `Message` · `Conversation` (+ `isGroup`/`groupId` fields) · `SignalSessionRecord` · `SessionEvent` · `Contact` · `Group` · `GroupMember`

**Application:** `DuoShieldApp`

All activities confirmed to extend `BaseActivity` (or are in the allowed-exceptions list: `BaseActivity` itself, `LockScreenActivity`, `SignInActivity`, `MainActivity`).

**Notable positive findings:**
- `ClipboardHelper.copy()` auto-clears the clipboard after 90 seconds — good security hygiene.
- `SearchResultsAdapter` already uses `DiffUtil` — no fix needed there.
- `PresenceThrottle` debounces at 2 s and auto-stops typing indicator after 3 s — prevents Firestore write floods.
- `TempFileCleaner` deletes decrypted voice/video temp files after 5 minutes — correct ephemeral-file handling.

---

## §A — FirebaseCostGuard Wiring (COMPLETE, 2026-06-15)

`FirebaseCostGuard` is a singleton that tracks daily Firestore reads, writes, and deletes against a configurable quota. Calling `canRead(n)` / `canWrite(n)` before a Firestore call and `recordReads(n)` / `recordWrites(n)` / `recordDeletes(n)` afterward enforces the budget.

### Hot paths wired

| Method / File | Guard call |
|---|---|
| `ChatMediaActivity.sendMessage()` | `canWrite(1)` + `recordWrites(1)` |
| `ChatMediaActivity.sendVoiceMessage()` | `canWrite(1)` + `recordWrites(1)` |
| `ChatMediaActivity.sendMediaMessage()` | `canWrite(1)` + `recordWrites(1)` |
| `ChatMediaActivity.sendContactCard()` | `canWrite(1)` + `recordWrites(1)` |
| `MessageBuilder.sendTextMessage()` | `canWrite(1)` + `recordWrites(1)` |
| `ConversationMetaUpdater.update()` | `recordWrites(1)` |
| `ConversationListActivity` listener | `recordReads(1)` per snapshot |
| `SelfDestructWorker.doWork()` | `recordReads(batch)` + `recordDeletes(batch)` |
| `GroupChatActivity.sendMessage()` | `canWrite(1)` + `recordWrites(1)` |
| `GroupChatActivity` listener | `recordReads(batch)` per snapshot |
| `CreateGroupActivity.createGroup()` | `canWrite(1)` × (group doc + key per member) |

---

## §B — Disappearing Messages Partner Sync (COMPLETE, 2026-06-15)

### Problem
When user A changed the disappear timer, user B's UI did not reflect the new setting until both sides happened to open Settings. There was no real-time sync.

### Changes made

#### `ChatMediaActivity.java`
- `syncDisappearToFirestore()` — writes `disappear_ms` and `disappear_set_by` (= `myUid`) to the Firestore conversation doc (`/chats/{conversationId}`). Called whenever the user picks a new timer via the header timer button.
- `listenForConvUpdates()` — existing Firestore listener extended: when it detects `disappear_ms` changed **and** `disappear_set_by != myUid`, it updates the local SharedPrefs key `disappear_ms` and shows a Snackbar: *"Partner set disappearing messages to X"*.

#### `SettingsActivity.java`
- `syncDisappearToFirestore()` — same write (identical logic, separate call site). Called after `showDisappearingPicker()` saves the new value.

### Firestore fields written (on `/chats/{conversationId}`)

| Field | Type | Meaning |
|---|---|---|
| `disappear_ms` | long | Active timer duration; 0 = off |
| `disappear_set_by` | String | UID of the device that wrote the field (prevents self-echo) |

---

## §C — Group Conversations (COMPLETE, 2026-06-15)

### Architecture overview

Groups are fully client-side encrypted using a shared **AES-256-GCM group key**. The key is generated by the creator and distributed to each member encrypted via their existing Signal session. Members decrypt the key on first open. Group messages never touch the Signal Double Ratchet — they use the shared symmetric group key directly.

### Encryption design

| Step | Detail |
|---|---|
| Key generation | `GroupCipherHelper.generateGroupKey()` → 32-byte random AES key, Base64 |
| Key distribution | Creator calls `SignalCipherHelper.encrypt(ctx, memberUid, groupKey)` for each member; stores ciphertext at `/groups/{id}/keys/{memberUid}` with fields `encryptedKey`, `sigType`, `senderUid` |
| Member key retrieval | On group open: if `Group.groupKey == null` in Room, fetch from Firestore → `SignalCipherHelper.decrypt(ctx, creatorUid, encryptedKey, sigType)` → persist to Room |
| Message encryption | `GroupCipherHelper.encrypt(plaintext, groupKeyB64)` — wire format: `[12-byte IV | ciphertext | 16-byte GCM tag]`, Base64 |
| Message decryption | `GroupCipherHelper.decrypt(ciphertextB64, groupKeyB64)` |

### Firestore paths

```
/groups/{groupId}                         Group document (name, createdBy, members[], lastActivity)
/groups/{groupId}/keys/{memberUid}        Encrypted group key per member (encryptedKey, sigType, senderUid)
/groups/{groupId}/messages/{msgId}        Group messages (same schema as /chats/{id}/messages but isEncrypted=true uses GroupCipherHelper)
```

### Room DB v12 schema (MIGRATION_11_12)

| Table | Columns |
|---|---|
| `contacts` | `uid` (PK), `displayName`, `conversationId`, `avatarUrl` |
| `groups` | `id` (PK), `name`, `avatarUrl`, `createdBy`, `createdAt`, `groupKey`, `lastMessage`, `lastMessageTs` |
| `group_members` | `groupId` + `memberUid` (composite PK), `displayName`, `joinedAt` |

`contacts` is populated by `PairingManager.finalizeConnection()` so paired contacts appear in the `CreateGroupActivity` member picker.

### New files

| File | Role |
|---|---|
| `models/Contact.java` | Room entity for paired contacts |
| `models/Group.java` | Room entity for group conversations |
| `models/GroupMember.java` | Room entity for group membership |
| `db/ContactDao.java` | `insert`, `getAll`, `getByUid`, `deleteByUid`, `deleteAll` |
| `db/GroupDao.java` | `insertGroup`, `getAllGroups`, `getGroupById`, `updateGroupKey`, `updateLastMessage`, `insertMember`, `insertMembers`, `getMembersOf`, `getMemberUidsOf`, `deleteMembersOf`, `deleteMember`, `deleteGroup` |
| `crypto/GroupCipherHelper.java` | AES-256-GCM group message encrypt / decrypt; `generateGroupKey()` |
| `GroupChatActivity.java` | Group chat screen; extends `BaseActivity`; single Firestore listener on `/groups/{id}/messages`; seeds from Room on open; sends encrypted messages; updates group `lastMessage` in Room |
| `ui/CreateGroupActivity.java` | Group creation: contact multi-select + group name + key generation + Signal-encrypted key distribution |
| `res/layout/activity_group_chat.xml` | Group chat layout (toolbar + RecyclerView + input bar) |
| `res/layout/activity_create_group.xml` | Create group layout (name input + contact list + create button) |
| `res/layout/item_member_select.xml` | Contact row with checkbox for the member picker |

### Modified files

| File | Change |
|---|---|
| `AppDatabase.java` | Version 11 → 12; added `Contact`, `Group`, `GroupMember` entities; added `contactDao()`, `groupDao()` abstract methods; added `MIGRATION_11_12` |
| `models/Conversation.java` | Added `isGroup` (boolean) and `groupId` (String) fields; added `Conversation.fromGroup(Group)` static factory |
| `pairing/PairingManager.java` | `finalizeConnection()` now inserts a `Contact` row into Room after pairing |
| `ConversationListActivity.java` | `loadGroupsFromRoom()` loads groups from Room on every `onStart()` and merges them (sorted by `lastMessageTs` desc) into `allConversations` via `mergeAndFilter()`; popup menu gains "New Group" item (id=5); `openChat()` routes to `GroupChatActivity` when `conv.isGroup == true` |
| `AndroidManifest.xml` | Registered `GroupChatActivity` and `ui.CreateGroupActivity` |

### Architecture rules followed
- `GroupChatActivity` extends `BaseActivity` (app-lock gate on `onStart()`) ✅
- Single Firestore listener — attached in `onStart()`, detached in `onStop()`, null-guarded ✅
- `MessageAdapter` with DiffUtil drives the RecyclerView ✅
- No `BiometricHelper` in any `onCreate()` ✅
- `FirebaseCostGuard` gates and records all Firestore operations ✅

### FCM for group messages
`notifyOnGroupMessage` Cloud Function in `functions/src/onGroupMessageCreated.ts` — triggered on `groups/{groupId}/messages/{messageId}` creation. Fans out FCM pushes to every member except the sender. Marks the message `delivered` with a server timestamp. Uses `Promise.allSettled()` so a stale token for one recipient doesn't block others. Deploy with `firebase deploy --only functions`.

---

## Outstanding / TODO

- [x] Image/video **encryption before upload** — AES-256-GCM; per-file random key stored as `mediaKey` in Firestore doc and Room `messages.mediaKey` column (v11). See §5.
- [x] Disappearing messages **UI toggle in ChatMediaActivity** — `@id/btnTimer` (`ic_timer`) added to chat header, wired to `showDisappearPicker()`
- [x] Message **forwarding** — `ForwardMessageHelper.forward()` wired in `showMessageActionDialog()` (case "Forward"); also added Copy and Edit actions
- [x] `MessageDao.deleteOlderThan()` — dead DAO method removed (2026-06-15)
- [x] **Unread badge** on launcher icon — `NotificationStyler.showMessage()` now takes `badgeCount`; `NotificationHelper` increments `badge_count` in SharedPrefs; `ChatMediaActivity.clearBadge()` + `MarkReadReceiver` reset it. See §5.
- [x] Play Store hardening — `shrinkResources true`, `debuggable false`, `versionCode 2` / `versionName "1.1"` in `app/build.gradle`; signing config placeholder comment added
- [x] `network_security_config.xml` — created at `app/src/main/res/xml/network_security_config.xml`; wired via `android:networkSecurityConfig` in `AndroidManifest.xml`; add HTTP domains as needed inside `<domain-config>`
- [x] Signal **signed pre-key rotation** — `SignedPreKeyRotationWorker` (WorkManager, daily check / 7-day rotation); `SignedPreKeyScheduler.schedule()` called from `DuoShieldApp.onCreate()`; grace-period "prev" SPK kept for one cycle so in-flight messages still decrypt. See §7.
- [x] **Audit F-02** — `FullScreenImageActivity.shareImage()` now calls `SecureShareHelper.shareImage()` (downloads bytes → FileProvider URI) instead of sharing raw B2 URL. `saveImageToGallery()` also downloads bytes before writing to MediaStore.
- [x] **Audit F-04** — `MainActivity.route()` gates FCM token Firestore upload behind `!AppLockManager.shouldLock(this)`; no network writes before PIN auth.
- [x] **Audit F-07** — `ChatMediaActivity` uses `Set<String> knownIds` (HashSet, O(1) lookup) + `latestKnownTimestamp` + Firestore `startAfter()` so only new messages are fetched on re-attach. Room loaded first when `knownIds` is empty.
- [x] **Audit F-09** — `ReactMessageHelper.react()` dead `arrayUnion` write removed; only the single `reaction` field is written.
- [x] Group conversations — `GroupCipherHelper` (AES-256-GCM shared key), `CreateGroupActivity`, `GroupChatActivity`, Room v12 schema (`contacts`/`groups`/`group_members`), `ConversationListActivity` merges groups into conversation list with "New Group" menu item. See §C.

### Message action dialog (2026-06-15)
`showMessageActionDialog()` now builds options dynamically:
- **All messages**: Pin/Unpin · Copy · Reply · React · Forward · Delete
- **Own text messages** (additional): Edit — only shown when `EditMessageHelper.canEdit()` passes (own message + within 48-hour window)
- `copyMessage()` — writes plaintext to system clipboard via `ClipboardManager`
- `showEditDialog()` — re-encrypts via `EditMessageHelper` and updates local Room via `adapter.updateMessage()`

---

## §D — Compile Fixes + Remaining Bug Hunt (COMPLETE, 2026-06-15)

### Compile errors fixed

| File | Bug | Fix |
|---|---|---|
| `GroupChatActivity.java` | `new MessageAdapter(myUid)` — wrong constructor (1 arg vs 4) | Changed to `new MessageAdapter(new ArrayList<>(), myUid, null, null)` |
| `GroupChatActivity.java` | `messageDao().getMessagesForConversation(groupId)` — method does not exist | Changed to `messageDao().getMessages(groupId)` |
| `CreateGroupActivity.java` | Creator's `GroupMember` row used `getString("my_uid", "Me")` — reads UID value as display name | Now reads Firebase Auth `displayName` → email → UID as ordered fallback |

### Logic fixes

| File | Bug | Fix |
|---|---|---|
| `ChatMediaActivity.java` | "Edit" shown for any own text msg regardless of age | Added `EditMessageHelper.canEdit()` guard — 48-hour window enforced at menu-build time |
| `ForwardMessageHelper.java` | Media messages forwarded as empty text (lost attachment) | Now calls `MessageBuilder.sendMediaMessage()` with existing `mediaUrl` + `mediaKey`; text messages keep `[Forwarded]` prefix |
| `MessageBuilder.java` | No `sendMediaMessage()` method | Added `sendMediaMessage(ctx, convId, myUid, partnerUid, storagePath, mediaType, mediaKey)` — writes to Firestore `chats/{id}/messages` with correct `path`/`mediaType`/`mediaKey` fields + Room insert |

### FLAG_SECURE — COMPLETE (v1.3)
`getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE)` added to every activity: `BaseActivity.onCreate()` (covers all 9 subclasses automatically) plus individually to `LockScreenActivity`, `SignInActivity`, `MainActivity`, `RestoreFromSeedActivity`, `SessionLogActivity`, `SeedPhraseDisplayActivity`. Prevents screen recording and recent-apps thumbnail leakage.

### §E — Group FCM Fan-out (COMPLETE, 2026-06-15)

| File | Role |
|---|---|
| `functions/src/onGroupMessageCreated.ts` | **New** — Cloud Function triggered on `groups/{groupId}/messages/{messageId}`; fans FCM to all members except sender; marks message `delivered` |
| `functions/src/index.ts` | Exports `notifyOnGroupMessage` |

Deploy: `cd functions && firebase deploy --only functions`

---

## Replit Environment (current — June 2026)

### Workflows
| Workflow | Command | Run button? |
|---|---|---|
| `Push Server` | `cd server && npm install --prefer-offline; node index.js` | ✅ Yes (port 3000) |
| `assembleRelease` | See below | No — trigger manually |

`assembleDebug` removed — release-only builds going forward.

### assembleRelease workflow command
```bash
bash setup-android-sdk.sh 2>/dev/null \
  && ANDROID_HOME=/home/runner/android-sdk ./gradlew --stop 2>/dev/null \
  ; python3 -c "import os; open('app/google-services.json','w').write(os.environ['GOOGLE_SERVICES_JSON'])" \
  && python3 -c "import os; open('app/src/main/assets/service-account.json','w').write(os.environ['GOOGLE_APPLICATION_CREDENTIALS_JSON'])" \
  && ANDROID_HOME=/home/runner/android-sdk ./gradlew :app:assembleRelease --no-daemon \
       "-Pandroid.injected.signing.store.file=$PWD/app/duoshield-release.keystore" \
       "-Pandroid.injected.signing.store.password=$KEYSTORE_PASSWORD" \
       "-Pandroid.injected.signing.key.alias=$KEY_ALIAS" \
       "-Pandroid.injected.signing.key.password=$KEY_PASSWORD" \
  2>&1 && echo RELEASE_APK_DONE
```
Output: `app/build/outputs/apk/release/app-release.apk` (~103 MB, ~4–5 min)

### Release Keystore
- File committed to repo: `app/duoshield-release.keystore`
- Alias: `duoshield` (lowercase)
- Algorithm: RSA 2048, validity 10 000 days
- Passwords stored as Replit secrets: `KEYSTORE_PASSWORD` / `KEY_PASSWORD`
- **No KEYSTORE_BASE64 needed** — workflow uses the file directly

### Replit Secrets (all configured ✅)
| Secret | Purpose |
|---|---|
| `GOOGLE_APPLICATION_CREDENTIALS_JSON` | Firebase service account (Push Server + FCM assets) |
| `GOOGLE_SERVICES_JSON` | google-services.json |
| `KEYSTORE_PASSWORD` | Release keystore store password |
| `KEY_ALIAS` | `duoshield` |
| `KEY_PASSWORD` | Release key password |
| `B2_KEY_ID` | Backblaze B2 key ID |
| `B2_APPLICATION_KEY` | Backblaze B2 application key |

Replit env vars (shared): `B2_REGION=ca-east-006`, `PUSH_SERVER_URL=https://duoshield.onrender.com`

### GitHub Actions secrets (for CI builds)
Same as above plus `KEYSTORE_BASE64` (base64 of `app/duoshield-release.keystore`).

---

## Backup System — Outstanding Work

Source: `attached_assets/DuoShield_Backup_System_-_Final_Scope_of_Improveme_(2)_1782572302804.md`

### Auth / key-derivation model (reminder)
- 12-word BIP39 mnemonic → 64-byte seed (PBKDF2-SHA512, 2048 iters)
- Seed → User ID: SHA-256 → first 8 bytes → Base32 `XXXXX-XXXXX-XXX`
- Seed → Signal identity keypair (HKDF-SHA256)
- Seed → Backup key: `SeedPhraseHelper.hkdfSha256(seed, "DUOSHIELD_BACKUP_V1", 32)`
- Mnemonic **never stored** — ephemeral only

### ✅ ALL BACKUP ITEMS COMPLETE (June 2026)

| # | Item | Status |
|---|---|---|
| 1 | Integrity checksums (SHA-256 stored + verified on restore) | ✅ Done |
| 2 | Firestore security rules — `backups` + `backup_logs` blocks | ✅ Done |
| 3 | Backup key derivation — `mnemonicToSeed()` → `hkdfSha256(..., "DUOSHIELD_BACKUP_V1", 32)` | ✅ Verified correct |
| 4 | Seed phrase audit — clipboard copy button removed from `SeedPhraseDisplayActivity` | ✅ Fixed |
| 5 | GZIP compression before AES-GCM (`encryptCompressed` / `decryptCompressed`) | ✅ Done |
| 6 | Incremental backup — `syncIncremental()`, `getMessagesSince()`, `last_backup_ts` prefs | ✅ Done |
| 7 | Backup monitoring — `logEvent()` → `backup_logs` Firestore collection | ✅ Done |
| 8 | Size limit warning at > 10 000 messages | ✅ Done |
| 9 | 90-day retention — `cleanupOldBackupsAsync()` wired in `ConversationListActivity.onCreate()` | ✅ Done |
| 10 | Key rotation | Explicitly skipped for MVP — requires full backup re-encryption |

### Key implementation notes
- `BackupSyncWorker` calls `syncIncremental()` (falls back to `syncAll()` on first run)
- Restore path auto-detects `compressed:true` flag; old uncompressed docs still readable
- `backup_logs` collection: write-only from client; query via Firebase Console or Cloud Functions
- Hard-delete blocked by Firestore rules; retention uses soft-delete (`isDeleted:true`)

---

## §UI — Premium Obsidian UI Overhaul (COMPLETE — June 2026)

### What changed
Full visual redesign targeting a luxurious dark-premium aesthetic. **No Java logic, IDs, or functionality was altered.**

#### Design tokens
| Token | Value |
|-------|-------|
| Background | `#04080F` |
| Surface / Surface2 | `#080E18` / `#0D1825` |
| Accent (electric cyan) | `#00C8E8` |
| Gradient | `#00E5FF` → `#0077A3` at 135° |
| Text primary | `#EDF3F7` |
| Text secondary | `#6E8FA0` |
| Bubble mine | `#005577`→`#00283D` gradient, 20dp/5dp corners |
| Bubble theirs | `#111F30`→`#0A1520` gradient + 1dp `#1A2D40` stroke |
| Danger | `#E8485A` |
| Online dot | `#00E676` |

#### Files updated
| Category | Files |
|----------|-------|
| Values | `colors.xml`, `themes.xml`, `text_appearances.xml` |
| Drawables (16) | `bg_bubble_mine/theirs`, `bg_button_gradient`, `bg_avatar_circle`, `bg_input_field_whatsapp`, `bg_send_button_whatsapp`, `bg_shield_glow`, `bg_online_dot`, `bg_fab`, `bg_settings_card`, `bg_input_field`, `bg_badge`, `bg_profile_avatar`, `bg_id_card`, `bg_hero_gradient`, `bg_reply_preview`, `bg_date_header` |
| Layouts (9) | `activity_sign_in`, `activity_splash`, `activity_conversation_list`, `activity_chat_media`, `activity_lock_screen`, `activity_settings`, `activity_pairing`, `item_conversation`, `item_message` |

#### Animated typing indicator — `TypingDotsView`
- New class: `com.duoshield.app.ui.TypingDotsView`
- 3 electric cyan dots (`#00C8E8`) bouncing in staggered wave (130ms delay each)
- Displayed inside a `bg_bubble_theirs`-styled pill within `typingIndicatorRow`
- Java `typingIndicator` TextView reference preserved as a 0×0 `gone` view
- Auto-starts in `onAttachedToWindow()`, auto-stops in `onDetachedFromWindow()`

#### ID safety rule
> Never change any `android:id` in layouts. Java uses `findViewById` against all existing IDs. Only visual/layout properties may be changed.

---

## Build Instructions

```bash
# Prerequisites:
# 1. Place app/google-services.json (Firebase console, project duoshield-8caf1)
# 2. Place app/src/main/assets/service-account.json (FCM HTTP v1 service account key)

./gradlew assembleRelease   # see Replit workflow command above for signing flags
./gradlew lint
```

### Replit release build (June 2026)
- Trigger the `assembleRelease` workflow — it injects both credential files automatically via Python, then signs with `app/duoshield-release.keystore`
- Required secrets: `GOOGLE_SERVICES_JSON`, `GOOGLE_APPLICATION_CREDENTIALS_JSON`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
- Output: `app/build/outputs/apk/release/app-release.apk` (~107 MB signed)
- To zip for download: `python3 -c "import zipfile; z=zipfile.ZipFile('out.zip','w',zipfile.ZIP_DEFLATED); z.write('app-release.apk'); z.close()"` (`zip` CLI unavailable in Replit)

---

## Part 0 — Reconciliation Findings (reference)

### Signal files verified present

| File | Key details |
|------|-------------|
| `crypto/signal/SignalKeyManager.java` | `ensureKeysInitialized(ctx)` generates all 4 key types; seed-derived identity key pair |
| `crypto/signal/SignalSessionManager.java` | `establishSession(ctx, recipientUid, callback)` — full X3DH via `SessionBuilder.process(PreKeyBundle)` |
| `crypto/signal/DuoShieldSignalStore.java` | `SignalProtocolStore` impl — SecurePrefs + Room-backed |
| `models/SignalSessionRecord.java` | Room entity. PK = `"{uid}.{deviceId}"` |
| `db/SignalSessionDao.java` | `load`, `store`, `delete`, `deleteAllForName`, `count`, `getAddressesForName` |

### Firestore public key bundle path

`/users/{uid}/public_keys/bundle` — fields: `identityKey`, `registrationId`, `signedPreKey` {`id`, `publicKey`, `signature`}, `oneTimePreKeys` [{`id`, `publicKey`} × 50]

---

## Part 2 — `DuressManager.performLogout()` (reference)

Exactly performs:
1. `SharedPreferences("duoshield_prefs").edit().clear().apply()`
2. Removes from SecurePrefs: `signal_identity_key_pair`, `signal_registration_id`, `signal_signed_prekey`, all `signal_prekey_{id}` keys, `signal_prekey_ids`
3. Does **NOT** remove `app_pin_hash` or `duress_pin_hash`
4. Deletes all `signal_sessions` Room rows
5. `FirebaseAuth.getInstance().signOut()` — local only
6. Launches `SignInActivity` with `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK`
7. Silent — no Toast, no dialog, no animation

Both triggers: (A) duress PIN match in `LockScreenActivity`, (B) 5th consecutive wrong PIN.
