# DuoShield — Security Remediation Plan

> **How this was built:** Two security review reports (13 + 1 rounds, 44 total findings) were read in
> full. The live codebase was then explored with parallel subagents to determine which findings have
> already been addressed and which still require work. Every fix listed here must be implemented
> and **cross-checked by a review subagent before the change is finalised** — that is a hard rule.

---

## Quick-Reference: Finding Status at Import

| # | Title | Severity | Status |
|---|-------|----------|--------|
| 1 | Release keystore committed to repo | Critical | ⚠️ Needs verification |
| 2 | Identity front-running window (onboarding) | High | ⚠️ Needs verification |
| 3 | Group key rotation missing | Medium | ⚠️ Needs verification |
| 4 | Deprecated `MasterKeys` API | Low | ❌ Open |
| 5 | Contact/group metadata plaintext in backups | Medium | ⚠️ Needs verification |
| 6 | No consent gate for calls/chats | Medium | ⚠️ Needs verification |
| 7 | Duress panic-sync deadline mismatch (5 s doc, 10 s actual) | Medium | ⚠️ Needs verification |
| 8 | No relay-only calling option | Medium | ⚠️ Needs verification |
| 9 | `CallCleanupWorker` dead code (always fails Firestore rules) | Low | ⚠️ Needs verification |
| 10 | Unauthenticated B2 delete via crafted message `path` | High | ✅ Fixed in B2CleanupWorker |
| 11 | B2/TURN credentials embedded in APK | High | ⚠️ Needs verification |
| 12 | Automatic link-preview IP/timing leak | High | ⚠️ Needs verification |
| 13 | Notification "Reply" resolves wrong/no partner | Medium | ❌ Open |
| 14 | "Wipe & Exit" missing Firebase `signOut()` | Medium | ❌ Open |
| 15 | Export PDF "removed after sharing" promise false | Medium | ⚠️ Needs verification |
| 16 | Duress audit-log race (log vs. delete ordering) | Medium | ❌ Open (fire-and-forget write) |
| 17 | `AppLockManager.coldStart` dead flag | Low | ⚠️ Needs verification |
| 18 | Signal session state corruption (no sync on encrypt/decrypt) | Critical | ✅ Fixed (SESSION_LOCKS) |
| 19 | Firestore `users/{uid}` create lets any auth user claim any UID | Critical | ⚠️ Needs verification |
| 20 | `FLAG_SECURE` missing (screenshots in app switcher) | Medium | ⚠️ Needs verification |
| 21 | "Delete for everyone" has no ownership check | Medium | ⚠️ Needs verification |
| 22 | `KeyFingerprintActivity` never shows key for new pairings | High | ✅ Fixed (per-address key storage) |
| 23 | VERIFY clears warning before check completes | Medium | ✅ Fixed (clears after confirmed match) |
| 24 | `sendContactCard()` sends plaintext, bypasses Signal | Medium | ❌ Open |
| 25 | `TempFileCleaner` misses `.m4a` voice files (checks `.3gp`) | Medium | ❌ Open |
| 26 | Disappearing-messages timer is global, not per-conversation | Medium | ❌ Open |
| 27 | Group key substitution via over-permissioned `keys/{memberUid}` | Critical | ✅ Fixed (write restricted to `createdBy`) |
| 28 | Group message sender spoofing / encryption bypass | High | ✅ Fixed (Firestore rule enforces sender==auth.uid and isEncrypted==true) |
| 29 | Any-time identity hijacking via unbound `identities/{userId}` rule | Critical | ✅ Fixed (rule checks path userId == auth.uid) |
| 30 | `SignInActivity` auto-route race undoes duress logout in real-time | Critical | ❌ Open |
| 31 | Biometric unlock never resets `pin_fail_count` | High | ❌ Open |
| 32 | "Wipe & Exit" leaves `pin_fail_count` behind | Medium | ❌ Open |
| 33 | `ChatMediaActivity` send path writes plaintext to `lastMessage` | High | ❌ Open |
| 34 | `RestoreFromSeedActivity` UID migration can never succeed | High | ⚠️ Needs verification |
| 35 | Duress logout writes human-readable "Duress logout" to Session Log | Critical | ❌ Open |
| 36 | `StorageDiagnosticsActivity` 403 error prints unmasked B2 key ID | Medium | ⚠️ Needs verification |
| 37 | Saved photos/videos persist through both wipe paths | Medium | ⚠️ Needs verification |
| 38 | Either 1:1 participant can forge partner's online/typing status | High | ⚠️ Needs verification |
| 39 | Pinned messages write uncapped plaintext to `chats.pinnedMessages[]` | High | ⚠️ Needs verification |
| 40 | `TextStyleHelper` markdown formatter has zero call sites | Low | ❌ Open (easy win) |
| 41 | `ReactMessageHelper` / `TypingThrottle` dead code | Low | ❌ Open (cleanup) |
| 42 | B2 Cleanup Worker reliability (sender-only scheduling) | Medium | ⚠️ Needs verification |
| 43 | `SelfDestructWorker` dead TTL logic / stale `firestore.indexes.json` | Low | ❌ Open (deleteOlderFromFirestore still present) |
| 44 | Identity hijacking confirmed (same root as 29, now closed) | Critical | ✅ Fixed (same fix as Finding 29) |

---

## What Is Already Fixed

These were confirmed fixed by reading the live source files:

### ✅ Finding 18 — Signal thread safety
`SignalCipherHelper` uses a static `ConcurrentHashMap<String, Object>` named `SESSION_LOCKS`.
Both `encrypt()` and `decrypt()` enter a `synchronized(lockFor(address))` block before touching
the `SessionCipher`. `EditMessageHelper` correctly dispatches its encrypt call to a background
executor so it doesn't block the main thread.

### ✅ Finding 22 — KeyFingerprintActivity per-contact key
`DuoShieldSignalStore.saveIdentity()` now writes `signal_partner_identity_key_<name>` (address-
scoped) on every identity save — not just on the key-changed branch. `KeyFingerprintActivity`
reads the per-contact key, not the old global slot.

### ✅ Finding 23 — VERIFY clears flag only after confirmed match
The "VERIFY" button in `ChatMediaActivity` now uses `startActivityForResult` / result-callback
and only clears `safety_num_changed_<partnerUid>` after `KeyFingerprintActivity` reports a
successful scan match, not on button-tap.

### ✅ Finding 27 — Group key substitution
`firestore.rules` — `groups/{groupId}/keys/{memberUid}` write is allowed only if
`request.auth.uid == get(...groups/{groupId}).data.createdBy`. An ordinary member cannot plant a
substitute key in another member's slot.

### ✅ Finding 28 — Group message sender spoofing
`groups/{groupId}/messages/{msgId}` rules now enforce on **create**:
`request.resource.data.sender == request.auth.uid` and `request.resource.data.isEncrypted == true`.
Update is not allowed (append-only). Delete is restricted to the original `sender`.

### ✅ Findings 29 / 44 — Identity hijacking via `identities/{userId}`
Rule now reads:
```
allow write: if request.auth != null
             && request.auth.uid == userId          // ← path must match caller
             && request.resource.data.uid == request.auth.uid;
```
Both the document-path check and the payload check are present.

### ✅ Finding 10 (partial) — B2 delete ownership
`B2CleanupWorker` now calls `B2StorageHelper.isOwnedB2Path(b2Path, chatId)` before issuing any
delete — path must be prefixed `media/<chatId>/`. `SelfDestructWorker.commitBatchDelete()` does
Firestore doc deletes only (WriteBatch), not direct B2 calls, so that path is not the concern.

---

## Open Findings — Prioritised Fix Order

Below is the full remediation queue, grouped by cluster. Each entry includes the **exact change**
needed, the **files involved**, and the **cross-check requirement**.

---

### 🔴 CLUSTER A — Duress / Plausible-Deniability Integrity (Findings 35, 30, 16)

These three findings all undermine the same core promise ("no way to prove duress mode was
triggered"). Fix them together in one pass so the ordering is coherent.

---

#### A1 · Finding 35 — Stop writing "Duress logout" to on-device Session Log *(Critical)*

**File:** `app/src/main/java/com/duoshield/app/security/DuressManager.java`

**Current code (line 148):**
```java
public static void performLogout(Context context) {
    // Log before destroying the session (DB write must happen first)
    SessionLogger.log(context, SessionLogger.DURESS_LOGOUT);
    ...
```

**Problem:** The `DURESS_LOGOUT` event type renders in `SessionLogActivity` as **"Duress logout"**
in red. Anyone who taps Settings → Session Log can see it, with a timestamp, during or after a
duress wipe.

**Fix — two-part:**
1. In `DuressManager.performLogout()`, change the `SessionLogger.log()` call to use
   `SessionLogger.SIGN_OUT` (indistinguishable from a voluntary sign-out):
   ```java
   SessionLogger.log(context, SessionLogger.SIGN_OUT);   // was DURESS_LOGOUT
   ```
2. In `SessionLogActivity.java` — verify the `DURESS_LOGOUT` case is either removed from the
   switch or mapped to the same display as `SIGN_OUT` (so any pre-existing row from before this
   fix also renders innocuously).

**Cross-check:** Subagent must confirm (a) no `DURESS_LOGOUT` string remains visible in the
rendered Session Log after the fix, and (b) `SessionLogger.DURESS_LOGOUT` constant may be
deleted or left as an unused constant — confirm no other caller references it.

---

#### A2 · Finding 30 — Close `SignInActivity` routing race *(Critical)*

**Files:** `DuressManager.java`, `SignInActivity.java`, `SplashActivity.java`, `MainActivity.java`

**Problem:** `performLogout()` launches `SignInActivity` immediately, but Firebase signOut and
`SecurePrefs.clear()` happen on a background thread after an async panic-sync. `SignInActivity`'s
`onCreate()` checks `FirebaseAuth.getCurrentUser() != null && SignalKeyManager.isInitialized(this)`
and auto-routes back into `ConversationListActivity` — before the background thread has destroyed
anything.

**Fix:**
1. In `DuressManager.performLogout()`, **before** starting the Intent, write a flag to plain
   (non-SecurePrefs) SharedPreferences:
   ```java
   context.getSharedPreferences("duoshield_prefs", Context.MODE_PRIVATE)
          .edit().putBoolean("duress_wipe_in_progress", true).commit(); // synchronous
   ```
2. As the **very last step** of the background thread (after `FirebaseAuth.signOut()`), clear it:
   ```java
   context.getSharedPreferences("duoshield_prefs", Context.MODE_PRIVATE)
          .edit().remove("duress_wipe_in_progress").apply();
   ```
3. In `SignInActivity.onCreate()` (and `onStart()`) auto-route guard, add:
   ```java
   boolean wipeInProgress = getSharedPreferences("duoshield_prefs", MODE_PRIVATE)
                              .getBoolean("duress_wipe_in_progress", false);
   if (!wipeInProgress && user != null && SignalKeyManager.isInitialized(this)) {
       route(user.getUid());
       return;
   }
   ```
4. Apply the same `wipeInProgress` guard to any equivalent check in `SplashActivity` and
   `MainActivity`.

**Cross-check:** Subagent must confirm all three guard sites (SignIn, Splash, Main) have the
flag check, and that the flag is cleared at the last step of the background thread only.

---

#### A3 · Finding 16 — Make the duress audit-log write ordered and safe *(Medium)*

**Files:** `SessionLogger.java`, `DuressManager.java`

**Problem:** `SessionLogger.log()` is fire-and-forget (uses a background executor, returns
immediately). `DuressManager` calls it first, but then `AppDatabase.clearInstance()` and
`context.deleteDatabase()` run on a separate background thread with no synchronization —
the delete can win the race, causing `SessionLogger`'s queued insert to create a brand-new
empty database containing only the "SIGN_OUT" row (after A1's fix, this is less damaging,
but the ordering guarantee is still worth enforcing).

**Fix:** After fixing A1 (using `SIGN_OUT` event type), also make the SessionLogger write
synchronous before the delete step. Move the `SessionLogger.log()` call to *inside* the
background thread, just before `AppDatabase.clearInstance()`, and use a synchronous Room
insert (wrap in `Tasks.await()` or just call `.get()` on the executor's `Future`):
```java
new Thread(() -> {
    BackupManager.syncIncrementalSync(context);
    // Write the sign-out log NOW, synchronously, before clearInstance()
    AppDatabase.getInstance(context).sessionEventDao()
               .insert(new SessionEvent(SessionLogger.SIGN_OUT, ...)); // blocking insert
    AppDatabase.clearInstance();
    context.deleteDatabase("duoshield_db");
    ...
```

**Cross-check:** Subagent must confirm `SessionLogger.log()` is no longer called before the
Intent launch, and the blocking insert lands before `clearInstance()`.

---

### 🔴 CLUSTER B — Lock Screen / PIN Counter (Findings 31, 32)

---

#### B1 · Finding 31 — Reset `pin_fail_count` on biometric success *(High)*

**File:** `app/src/main/java/com/duoshield/app/LockScreenActivity.java`

**Current `showBiometric()` (lines 102–107):**
```java
private void showBiometric() {
    BiometricHelper.authenticate(this, new BiometricHelper.AuthCallback() {
        @Override public void onSuccess() { unlock(); }   // ← never resets counter
        @Override public void onFailure() { etPin.requestFocus(); }
    });
}
```

**Fix:** Mirror what `checkPin()`'s correct branch already does:
```java
@Override public void onSuccess() {
    getSharedPreferences("duoshield_security_prefs", MODE_PRIVATE)
        .edit().putInt("pin_fail_count", 0).apply();
    unlock();
}
```

**Cross-check:** Subagent must confirm the exact SharedPrefs file name and key name match what
`checkPin()`'s correct branch writes — they must be identical strings.

---

#### B2 · Finding 32 — Clear `pin_fail_count` in voluntary wipe *(Medium)*

**File:** `app/src/main/java/com/duoshield/app/util/WipeHelper.java`

**Current `wipeAll()` does NOT clear `duoshield_security_prefs`.** `DuressManager` does clear it.

**Fix:** Add before or after the existing `duoshield_prefs` clear:
```java
context.getSharedPreferences("duoshield_security_prefs", Context.MODE_PRIVATE)
       .edit().clear().commit();
```

**Cross-check:** Subagent must confirm this line is added to `wipeAll()` and is synchronous
(`.commit()` not `.apply()`), consistent with the rest of the wipe.

---

### 🟠 CLUSTER C — Plaintext Leaks into Firestore (Findings 33, 39)

---

#### C1 · Finding 33 — `lastMessage` plaintext in `ChatMediaActivity` send path *(High)*

**File:** `app/src/main/java/com/duoshield/app/ChatMediaActivity.java` (~lines 2392–2393)

**Current code:**
```java
ConversationMetaUpdater.update(ChatMediaActivity.this, conversationId, myUid,
    partnerUid, plaintext.length() > 80 ? plaintext.substring(0, 80) : plaintext);
```

**Fix:** Use the same placeholder pattern already used in `MessageBuilder.java`:
```java
ConversationMetaUpdater.update(ChatMediaActivity.this, conversationId, myUid,
    partnerUid, "\uD83D\uDD12 New message");
```

**Cross-check:** Subagent must grep for ALL call sites of `ConversationMetaUpdater.update()`
across the entire codebase and confirm every one passes a non-plaintext value (placeholder or
media-type label). Must also verify `MessageBuilder.java`'s two call sites are still correct.

---

#### C2 · Finding 39 — Pinned messages write uncapped plaintext *(High)*

**File:** `app/src/main/java/com/duoshield/app/ChatMediaActivity.java` (`pinMessage()`, ~lines 1528–1543)

**Problem:** `pinMessage()` puts `msg.getText()` (full decrypted plaintext, no cap) into
`pinnedMessages[].preview` in the Firestore chat document. Unlike `lastMessage`, this is NOT
overwritten on next send — it persists until manually unpinned.

**Fix:** Replace `msg.getText()` with a fixed content-free label, e.g.:
```java
"📌 Pinned message"     // or truncate + replace: msg.getText().substring(0,40) + "…"
```
The safest approach is a non-content label, consistent with the `lastMessage` fix above.

**Cross-check:** Subagent must read the full `pinMessage()` method and confirm there is no
other location where message text is written to the Firestore chat document unencrypted.

---

### 🟠 CLUSTER D — Wipe / Session Cleanup (Findings 14, 37)

---

#### D1 · Finding 14 — "Wipe & Exit" missing `Firebase.signOut()` *(Medium)*

**File:** `app/src/main/java/com/duoshield/app/util/WipeHelper.java`

**Current `wipeAll()` clears Room, SecurePrefs, duoshield_prefs, cache — but never calls
`FirebaseAuth.getInstance().signOut()`.**

**Fix:** After `AppDatabase.clearInstance()` and before navigating to `SignInActivity`, add:
```java
try { FirebaseAuth.getInstance().signOut(); } catch (Exception ignored) {}
```

**Cross-check:** Subagent must confirm the signOut() call is present and that the navigation
to SignInActivity still happens after it.

---

#### D2 · Finding 37 — Saved photos/videos survive both wipe paths *(Medium)*

**Files:** `WipeHelper.java`, `DuressManager.java`, `FullScreenImageActivity.java`,
`MediaViewerActivity.java`

**Problem:** When a user saves a received image/video via "Save" / "Download", the bytes are
written to `MediaStore` (`Pictures/DuoShield`, `Movies/DuoShield`). Neither `wipeAll()` nor
`performLogout()` deletes those `MediaStore` URIs.

**Fix:**
1. Create a small helper `MediaStoreWipeHelper` that reads a list of saved URIs from a local
   Room table (or plain SharedPrefs JSON list) and calls
   `getContentResolver().delete(uri, null, null)` for each.
2. In `FullScreenImageActivity.writeImageToGallery()` and `MediaViewerActivity.writeVideoToGallery()`,
   after a successful write, record the resulting `Uri` (returned by `ContentResolver.insert()`).
3. Call `MediaStoreWipeHelper.wipeAll(context)` from both `WipeHelper.wipeAll()` and
   `DuressManager.performLogout()`.

**Cross-check:** Subagent must confirm URIs are recorded on save and deleted on both wipe paths.

---

### 🟠 CLUSTER E — Notification Reply (Finding 13)

---

#### E1 · Finding 13 — `MessageReplyReceiver` resolves wrong partner *(Medium)*

**Files:** `NotificationStyler.java`, `MessageReplyReceiver.java`

**Current:** `showMessage()` builds `replyIntent` with only `EXTRA_CONV_ID` and `EXTRA_MY_UID`.
`partnerUid` is in scope but never added. `MessageReplyReceiver` falls back to a stale
`partner_uid` SharedPref that is no longer maintained.

**Fix (one line in `NotificationStyler`):**
```java
replyIntent.putExtra("partner_uid", partnerUid);
```
In `MessageReplyReceiver.onReceive()`, read:
```java
String partnerUid = intent.getStringExtra("partner_uid");
if (partnerUid == null) {
    partnerUid = prefs.getString("partner_uid", null); // legacy fallback only
}
```

**Cross-check:** Subagent must confirm both the put and the read, and confirm no other
`MessageReplyReceiver` path forgets to forward the value.

---

### 🟠 CLUSTER F — Contact Card Encryption (Finding 24)

---

#### F1 · Finding 24 — `sendContactCard()` sends plaintext *(Medium)*

**File:** `app/src/main/java/com/duoshield/app/ChatMediaActivity.java` (`sendContactCard()`, ~lines 2278–2307)

**Current:** Sets `doc.put("isEncrypted", false)` and writes `cardText` literally.

**Fix:** Route through `SignalCipherHelper.encrypt()` exactly like `sendMessage()`:
```java
SignalCipherHelper.EncryptResult r = SignalCipherHelper.encrypt(this, partnerUid, cardText);
doc.put("text", r.ciphertextB64);
doc.put("isEncrypted", true);
doc.put("sigType", r.sigType);
```
Keep the `contact_card` / `mediaType` tag so the receiving side still renders it as a card.

**Cross-check:** Subagent must verify the receiving-side decrypt path in `ChatMediaActivity`'s
`listenForMessages()` handles a contact card message with `isEncrypted: true` correctly (i.e.,
decrypts then parses the `DuoShield User|uid` format after decryption).

---

### 🟡 CLUSTER G — TempFileCleaner Extension Mismatch (Finding 25)

---

#### G1 · Finding 25 — `.m4a` voice files never cleaned up *(Medium)*

**File:** `app/src/main/java/com/duoshield/app/util/TempFileCleaner.java` (`isTempMediaFile()`, ~line 95)

**Current:**
```java
return (name.startsWith("voice_") && name.endsWith(".3gp"))
    || (name.startsWith("vid_")   && name.endsWith(".mp4"))
    || ...
```

**Fix:** Add `.m4a` as a matched extension alongside `.3gp`:
```java
return (name.startsWith("voice_") && (name.endsWith(".3gp") || name.endsWith(".m4a")))
    || (name.startsWith("vid_")   && name.endsWith(".mp4"))
    || ...
```
Also add `f.delete()` to the exhausted-retries branch of `ChatMediaActivity.uploadVoiceNoteWithRetry()`
so failed uploads don't leave the source file behind.

**Cross-check:** Subagent must grep for every `File.createTempFile("voice_",` or
`File(ctx.getCacheDir(), "voice_"` call site and confirm all of them use `.m4a`. If any
still use `.3gp`, the extension check must cover both.

---

### 🟡 CLUSTER H — Disappearing Messages Scope (Finding 26)

---

#### H1 · Finding 26 — `disappear_ms` is a global pref, not per-conversation *(Medium)*

**Files:** `ChatMediaActivity.java` (getter/setter/sync-listener), `MessageBuilder.java` (two reads)

**Problem:** `"disappear_ms"` in `duoshield_prefs` is a single key. Opening chat with contact B
overwrites the timer used by the currently-open chat with contact A.

**Fix:** Scope the key by `conversationId` everywhere it is read or written:
- `getDisappearMs()` → reads `"disappear_ms_" + conversationId`
- `showDisappearPicker()` write → `"disappear_ms_" + conversationId`
- `listenForConvUpdates()` partner-sync write → `"disappear_ms_" + conversationId`
- `MessageBuilder.sendTextMessage()` (both call sites) — `MessageBuilder` must receive
  `conversationId` as an explicit parameter and read `"disappear_ms_" + conversationId`

**Cross-check:** Subagent must grep for every `"disappear_ms"` string and `disappear_ms`
SharedPrefs key across the entire codebase and confirm every read/write is now scoped.

---

### 🟡 CLUSTER I — Firestore Rules Gaps (Findings 38, 21)

---

#### I1 · Finding 38 — Either 1:1 participant can forge partner's status fields *(High)*

**File:** `firestore.rules` — `match /chats/{chatId}` update rule

**Problem:** The rule allows any participant to update *any* field of the chat document,
including `online_<partnerUid>`, `lastSeen_<partnerUid>`, `typing_<partnerUid>`,
`unread_<partnerUid>` — fields that should only be writable by the UID in the suffix.

**Fix:** Restrict update to only fields whose suffix matches the caller's UID. This is hard
to express purely in rules with dynamic keys, but a practical approach is:
```
allow update: if request.auth.uid in resource.data.participants
              && request.resource.data.diff(resource.data).affectedKeys()
                   .hasOnly(['online_' + request.auth.uid,
                             'lastSeen_' + request.auth.uid,
                             'typing_' + request.auth.uid,
                             'unread_' + request.auth.uid,
                             'lastMessage', 'lastMessageTime',
                             'disappear_ms', 'disappear_set_by',
                             'pinnedMessages']);
```
The `pinnedMessages` and `lastMessage` entries can be tightened further once the plaintext
issues (C1, C2) are fixed.

**Cross-check:** Subagent must verify the allowed-keys list covers every field written by
`OnlinePresenceHelper`, `PresenceThrottle`, `ReadReceiptHelper`, `UnreadCountHelper`, and
`ConversationMetaUpdater`. Missing a legitimate field will break the app.

---

#### I2 · Finding 21 — "Delete for everyone" lets recipient erase sender's messages *(Medium)*

**File:** `firestore.rules` — `match /chats/{chatId}/messages/{msgId}` update rule

**Problem:** Any participant can set `deletedForAll: true` on any message regardless of who
sent it.

**Fix:**
```
allow update: if request.auth.uid in get(...chats/{chatId}).data.participants
              && (!('deletedForAll' in request.resource.data.diff(resource.data).affectedKeys())
                  || resource.data.sender == request.auth.uid);
```
Also gate the UI: in `ChatMediaActivity.showMessageActionDialog()`, show "Delete for everyone"
only when `msg.getSenderId().equals(myUid)`.

**Cross-check:** Subagent must confirm both the Firestore rule change and the UI gate, and
verify the rules test file (`firestore-tests/rules.test.js`) has a test that proves a
recipient cannot delete-for-everyone on a message they didn't send.

---

### 🟡 CLUSTER J — Storage Diagnostics Credential Leak (Finding 36)

---

#### J1 · Finding 36 — Unmasked B2 key ID in 403 error card *(Medium)*

**File:** `app/src/main/java/com/duoshield/app/ui/StorageDiagnosticsActivity.java` or
`B2StorageHelper.java` `testConnection()` method (~lines 732–786)

**Problem:** The 403-error string uses `getKeyId()` (full key) instead of `getMaskedKeyId()`.

**Fix:** Single swap:
```java
// Before:
"... B2_KEY_ID='" + getKeyId() + "' ..."
// After:
"... B2_KEY_ID='" + getMaskedKeyId() + "' ..."
```

**Cross-check:** Subagent must grep for every `getKeyId()` call and confirm none print to
a user-visible `TextView` or log at `Log.d`/`Log.i` level — only `getMaskedKeyId()` should
appear in user-visible strings.

---

### 🟢 CLUSTER K — Dead Code / Cleanup (Findings 9, 17, 40, 41, 43)

These carry no confidentiality impact. Fix in a single cleanup pass.

#### K1 · Finding 40 — Wire up `TextStyleHelper` in `MessageAdapter` *(Low)*

**File:** `app/src/main/java/com/duoshield/app/ui/MessageAdapter.java` (~line 550)

```java
// Before:
h.textView.setText(msg.getText());
// After:
TextStyleHelper.apply(h.textView, msg.getText());
```
Also reorder `applyPattern()` in `TextStyleHelper` to run `MONO` first (protecting backtick
spans from bold/italic matching).

#### K2 · Finding 43 — Remove dead TTL logic from `SelfDestructWorker` *(Low)*

**File:** `app/src/main/java/com/duoshield/app/db/SelfDestructWorker.java`
- Delete `deleteOlderFromFirestore()` method and its call site.
- Remove `self_destruct_minutes` / `self_destruct_enabled` SharedPrefs reads.
- Prune `firestore.indexes.json` of any `selfDestructAt` index.

#### K3 · Finding 41 — Delete dead util classes *(Low)*

Delete `ReactMessageHelper.java` and `TypingThrottle.java` after confirming zero callers via grep.

#### K4 · Finding 9 — `CallCleanupWorker` dead code *(Low)*

Cancel its WorkManager scheduling from the client. Move cleanup logic to `server/index.js`
using the Admin SDK (which bypasses Firestore rules).

#### K5 · Finding 17 — Remove dead `coldStart` flag from `AppLockManager` *(Low)*

Remove the field and its write site; the existing `bgTs == 0` check in `shouldLock()` already
handles the cold-start case correctly.

#### K6 · Finding 4 — Migrate `MasterKeys` to `MasterKey.Builder` *(Low)*

**File:** `SecurePrefs.java` — routine maintenance, no urgency.

---

### ⬛ CLUSTER L — Needs Verification Before Planning a Fix

These findings have not yet been confirmed open or closed in the live code. Each needs a
targeted read before any fix is written.

| # | What to verify |
|---|----------------|
| 1 | Is `app/duoshield-release.keystore` in `.gitignore`? Run `git log --all --name-only -- app/duoshield-release.keystore` to check history |
| 2 | `AuthTokenHelper.java` — is the identity front-running window still present during onboarding? |
| 5 | `BackupManager.java` — does the cloud backup include contact/group metadata in plaintext? |
| 7 | `BackupManager.syncIncrementalSync()` — is the actual deadline 5 s or 10 s? |
| 11 | `B2StorageHelper.java` — are B2 credentials still hardcoded as `BuildConfig` fields? |
| 12 | `MessageAdapter.bindLinkPreview()` — is the link-preview fetch still recipient-side and automatic? |
| 15 | `ExportHelper.java` — is the "removed after sharing" claim still in the dialog? Is there a callback? |
| 19 | `firestore.rules` — `users/{uid}` create — can any user claim any UID, or is the rule now bound? |
| 20 | `BaseActivity.onCreate()` — is `FLAG_SECURE` still absent? (intentional per memory; add before release) |
| 21 | `ChatMediaActivity.showMessageActionDialog()` — is "Delete for everyone" gated on `mine`? |
| 34 | `RestoreFromSeedActivity.migrateOldUid()` — do the queries now work under current Firestore rules? |
| 36 | `B2StorageHelper.testConnection()` — confirmed `getKeyId()` vs `getMaskedKeyId()` in 403 branch |
| 38 | `firestore.rules` — `chats/{chatId}` update rule — is field-level scoping present? |
| 39 | `ChatMediaActivity.pinMessage()` — does it pass `msg.getText()` or a placeholder to `pinnedMessages`? |
| 42 | `B2CleanupWorker.schedule()` — is it still called only from sender's device on upload success? |
| 43 | `firestore.indexes.json` — does it still contain stale `selfDestructAt` index? |

---

## Implementation Rules (Non-Negotiable)

1. **Every code change must be cross-checked by a review subagent before committing.** The
   reviewer must read the changed file in context and confirm: (a) the fix matches the exact
   change described here, (b) no regression is introduced in adjacent code, (c) every call site
   is updated if a method signature changes.

2. **Compile after each cluster.** Use the registered validation command
   (`:app:compileDebugJavaWithJavac`) after completing each cluster before moving to the next.

3. **Do not change layout IDs.** Per project memory — changing resource IDs without a full audit
   breaks binding references silently.

4. **Firestore rules changes must be deployed.** After editing `firestore.rules`, run
   `firebase deploy --only firestore:rules` and check the Firebase console confirms the new
   rules are live.

5. **Work one cluster at a time.** Do not begin the next cluster until the previous one compiles
   clean and passes review.

---

## Suggested Implementation Order

```
Week 1 (Critical path):
  Cluster A  — Duress integrity (35 → 30 → 16)   [most impactful, tightly coupled]
  Cluster B  — PIN counter fixes (31 → 32)        [one-liners, low risk]

Week 2 (High severity):
  Cluster C  — Plaintext leaks (33 → 39)
  Cluster I  — Firestore rules gaps (38 → 21)
  Cluster E  — Notification reply fix (13)

Week 3 (Medium severity):
  Cluster D  — Wipe gaps (14 → 37)
  Cluster F  — Contact card encryption (24)
  Cluster G  — TempFileCleaner .m4a fix (25)
  Cluster H  — Disappearing messages scoping (26)
  Cluster J  — StorageDiagnostics key masking (36)

Week 4 (Verification + Cleanup):
  Cluster L  — Verify all uncertain findings, plan fixes for confirmed-open ones
  Cluster K  — Dead code cleanup (40, 43, 41, 9, 17, 4)
```
