# DuoShield Security Review — Resolution Roadmap

Source: `attached_assets/DuoShield_Security_Review_1783356874664.md` (7 review rounds, 29 findings).

## Ground rules for every item on this roadmap

1. **One finding at a time.** Never batch unrelated findings into the same edit pass — it makes regressions hard to attribute.
2. **Read before writing.** Re-read the current source (not just the review's quoted snippet) immediately before editing — the review is a point-in-time snapshot and line numbers may have drifted.
3. **Minimal, targeted diff.** Fix exactly what the finding describes. Do not refactor surrounding code "while we're in there."
4. **Mandatory subagent verification before marking a finding done.** After every fix:
   - Run `code_review` (architect) subagent against the diff, explicitly asking it to check: (a) the specific vulnerability is actually closed, (b) no other call site of the changed function/rule was broken, (c) no new bug was introduced, (d) Room schema/migration rules were respected if a DB change was involved.
   - If the finding touches `firestore.rules`, additionally verify every legitimate call site listed in the finding still passes the new rule (trace it manually, and check `firestore-tests/` if a matching test exists — add one if not).
   - If the finding touches a Java file that affects compilation, run the `build` validation (`:app:compileDebugJavaWithJavac`) and `lint` validation before marking done.
   - Only mark `[x]` after the subagent confirms no regression. If it flags an issue, fix and re-verify before moving on.
5. **Update this file as you go** — flip `[ ]` → `[x]`, and add a one-line note of what changed + which subagent check confirmed it.

---

## Priority order and status

### 🔴 Critical — fix first, no exceptions

- [x] **F29 — `identities/{userId}` write rule doesn't bind to the path, allowing permanent DS-ID hijack of any existing account**
  Files: `firestore.rules` (`match /identities/{userId}`), `ui/SeedPhraseDisplayActivity.java`, `ui/RestoreFromSeedActivity.java`
  Fix: `allow write: if request.auth != null && request.auth.uid == userId && request.resource.data.uid == request.auth.uid;`
  Verified: architect subagent confirmed all legitimate write call sites (custom-token UID == DS-ID by construction, `SeedPhraseDisplayActivity.registerIdentity`, `RestoreFromSeedActivity`) already satisfy `auth.uid == userId`; no broken call sites; `:app:compileDebugJavaWithJavac` passed. Added regression tests to `firestore-tests/rules.test.js`.

- [x] **F19 — `users/{uid}/public_keys/bundle` update rule lets any authenticated user overwrite another user's identityKey/signedPreKey (X3DH MITM)**
  Files: `firestore.rules` (`match /users/{uid}/public_keys/{doc}`), `crypto/signal/SignalKeyManager.java`, `crypto/signal/SignalSessionManager.java`
  Fix: scope cross-user `update` to only `oneTimePreKeys`/`updatedAt`: `request.resource.data.diff(resource.data).affectedKeys().hasOnly(['oneTimePreKeys','updatedAt'])`.
  Verified: architect subagent confirmed `consumeOtpkOnFirestore()` only ever touches `oneTimePreKeys` via `arrayRemove`, so it still succeeds; cross-user writes to `identityKey`/`signedPreKey` (even smuggled alongside `oneTimePreKeys`) now fail. Added regression tests to `firestore-tests/rules.test.js`.

- [x] **F27 — Group per-member key slot (`groups/{groupId}/keys/{memberUid}`) writable by any member, not just the creator — enables targeted group key substitution**
  Files: `firestore.rules` (`match /keys/{memberUid}`, `match /groups/{groupId}`), `GroupChatActivity.java` (`fetchGroupKey()`)
  Fix: restrict `allow write` on the key slot to `request.auth.uid == get(/databases/$(database)/documents/groups/$(groupId)).data.createdBy`; PLUS made `createdBy` immutable on the parent `groups/{groupId}` doc (`request.resource.data.createdBy == resource.data.createdBy`) after the first architect pass caught a two-step bypass (member rewrites `createdBy` to self, then passes the creator-only check). Defense-in-depth: `fetchGroupKey()` compares fetched `senderUid` against locally-cached `creatorUid`, refuses/warns on mismatch.
  Verified: two architect subagent passes — first flagged the `createdBy` mutability gap, second confirmed the immutability fix closes it and that no legitimate client flow (`CreateGroupActivity`, `GroupChatActivity`, `RestoreFromSeedActivity`) ever needs to change `createdBy` after creation. `:app:compileDebugJavaWithJavac` passed. Added regression tests (including the two-step escalation attempt) to `firestore-tests/rules.test.js`.

- [x] **F18 — Signal Protocol encrypt/decrypt not globally serialized per-address — concurrent notification-reply/edit/chat-screen calls can silently corrupt Double Ratchet state**
  Files: `crypto/signal/SignalCipherHelper.java`, `crypto/signal/DuoShieldSignalStore.java`, `util/MessageBuilder.java`, `util/EditMessageHelper.java`
  Fix: added a static `ConcurrentHashMap<String, Object>` of per-`SignalProtocolAddress` lock objects in `SignalCipherHelper`; `encrypt()`/`decrypt()` synchronize on the lock for the full load→mutate→store span, mirroring upstream libsignal's own static session lock pattern.
  Verified: architect subagent traced `MessageBuilder`/`EditMessageHelper`/`ChatMediaActivity` and found no nested encrypt→decrypt (or vice versa) call chain for the same address, so no deadlock risk; confirmed existing single-thread executors layer safely under the new lock; confirmed lock granularity (uid + fixed `DEVICE_ID`) matches DuoShield's 1:1 single-device model. `:app:compileDebugJavaWithJavac` passed.

- [x] **F1 — Release signing keystore committed to repo, not git-ignored**
  Files: `app/duoshield-release.keystore`, `.gitignore`
  Fix: added `*.keystore` / `*.jks` to `.gitignore` to prevent future keystore commits.
  Decision: repo history is shallow (grafted import — only 3 commits visible), so full prior-exposure scope cannot be determined here. User was informed of the options (untrack-only vs. full rotation) and chose to leave the already-committed copy as-is for now, with a note to revisit the rotation decision before any public repo sharing or Play Store submission.
  Verified: `.gitignore` now prevents future keystore commits; build workflow decodes the keystore from `KEYSTORE_BASE64` secret at build time (not from git) so CI is unaffected by the ignore rule.

- [x] **F20 — Screenshot-protection default inverted between what Settings displays and what BaseActivity/MainActivity/LockScreenActivity enforce**
  Files: `BaseActivity.java`, `ui/SettingsActivity.java`, `LockScreenActivity.java`, `MainActivity.java`
  Fix: changed all three enforcement call sites' default from `getBoolean("app_screenshot_enabled", true)` to `getBoolean("app_screenshot_enabled", false)`, matching `SettingsActivity`'s existing `false` display default.
  Verified: architect subagent confirmed all 4 sites (3 enforcement + Settings) now consistently default to "screenshots blocked"; confirmed `SharedPreferences.getBoolean` only falls back to the default when the key is absent, so users who previously stored `true` (opted into allowing screenshots) are unaffected; repo-wide scan found no other runtime call site still using the old `true` default. `:app:compileDebugJavaWithJavac` passed.

### 🟠 High

- [x] **F10 — Any received message can trigger deletion of an arbitrary object from the shared media bucket (no ownership check on `path`)**
  Files: `util/B2StorageHelper.java` (`deleteFile()`), `db/SelfDestructWorker.java`, `db/B2CleanupWorker.java`, `ChatMediaActivity.java`
  Fix: `B2StorageHelper.isOwnedB2Path(b2Path, conversationId)` helper added — validates B2 prefix + second path segment equals conversationId. `SelfDestructWorker.commitBatchDelete()` calls it before every `deleteFile()`, using `docRef.getParent().getParent().getId()` to extract chatId from the Firestore `chats/{chatId}/messages/{msgId}` DocumentReference chain. `B2CleanupWorker.doWork()` calls it before its `deleteFile()`, with chatId from WorkManager input data.
  Verified: architect subagent confirmed Firestore reference chain is correct for both workers; no legitimate path regression found; `:app:compileDebugJavaWithJavac` passed.

- [x] **F28 — Group messages carry a client-asserted `sender`/`isEncrypted` field the recipient trusts unconditionally (forgery/encryption-bypass)**
  Files: `firestore.rules` (`match /groups/{groupId}/messages/{msgId}`), `GroupChatActivity.java` (`listenForMessages()`, `trySend()`)
  Fix: rule split into read (member), create (member + `sender == auth.uid` + `isEncrypted == true`), delete (sender only); update removed (append-only). `GroupChatActivity.trySend()` writes `isEncrypted:true` in the doc.
  Verified: architect subagent confirmed `trySend()` includes `isEncrypted:true`; no group-message update path exists so no-update rule breaks nothing; `:app:compileDebugJavaWithJavac` passed.

- [x] **F22 — Safety-number/fingerprint verification never has anything to show for a first-time pairing (defeats the one mitigation for F19)**
  Files: `crypto/signal/DuoShieldSignalStore.java` (`saveIdentity()`), `KeyFingerprintActivity.java`, `ChatMediaActivity.java`, `ConversationListActivity.java`
  Fix: `DuoShieldSignalStore.saveIdentity()` now writes `signal_partner_identity_key_<address.getName()>` on BOTH first-use and key-change branches. `KeyFingerprintActivity` resolves partnerUid from: (1) Intent extra `partner_uid`, (2) SharedPrefs `partner_uid`, (3) legacy unscoped fallback key. `ChatMediaActivity.setupChat()` now writes `partner_uid` to SharedPrefs on every chat open (fixes multi-contact availability). `ChatMediaActivity.checkSafetyNumberBanner()` passes `partner_uid` Intent extra when launching fingerprint screen. `ConversationListActivity` global menu reads SharedPrefs partner_uid and passes it via Intent. `KeyFingerprintActivity` shows "open from a chat conversation to verify a specific contact" hint when no partner can be resolved.
  Verified: two architect subagent passes; chat-banner path confirmed fixed for first pairing; multi-contact partner_uid persistence confirmed; no regression on legacy unscoped fallback; `:app:compileDebugJavaWithJavac` passed.

### 🟡 Medium

- [x] **F23 — `KeyFingerprintActivity` has no way to know which contact it's verifying; VERIFY clears the warning before any check completes**
  Files: `KeyFingerprintActivity.java`, `ConversationListActivity.java`, `ChatMediaActivity.java` (`checkSafetyNumberBanner()`)
  Fix: `partner_uid` Intent extra now passed by both launch sites (ChatMediaActivity banner + ConversationListActivity menu). `clearSafetyNumOnMatch` Intent extra added so only the banner-VERIFY path can clear the flag. `KeyFingerprintActivity.onScanResult()` clears `safety_num_changed_<partnerUid>` only on fingerprint match + `clearSafetyNumOnMatch==true`. Banner hides for the session on VERIFY tap but flag persists until a successful QR scan. Javadoc updated to reflect new behavior.
  Verified: architect subagent confirmed banner reappears on next `onResume()` if QR scan not completed; successful match clears flag permanently; mismatch preserves flag; non-banner launch paths unaffected (default `clearSafetyNumOnMatch=false`). `:app:compileDebugJavaWithJavac` passed.

- [x] **F2 — New-account identity registration has a front-running race window (`/mintToken` mints before `identities` doc exists)**
  Files: `server/index.js` (`/mintToken`), `ui/SeedPhraseDisplayActivity.java`
  Fix: `/mintToken` now uses `db.runTransaction()` to atomically claim `identities/{userId}` (first caller wins; concurrent first-claims for same userId serialized by transaction) before minting the custom token. Token is only issued after the transaction succeeds. For existing accounts hash is re-verified inside the same transaction. `SeedPhraseDisplayActivity.registerIdentity()` changed to `SetOptions.merge()` so it's idempotent and cannot overwrite the server-written `identityPubKeyHash`. Error path in catch handles `e.status === 403` to return correct HTTP 403 for hash mismatches thrown from inside the transaction. Security-model comment updated.
  Verified: architect subagent confirmed race is closed, Restore flow remains compatible, body variable in scope in catch, throwing inside Admin SDK transaction is correct usage. `:app:compileDebugJavaWithJavac` passed.

- [ ] **F6 — No consent/contact gate for calls or chat creation — any UID can ring or message any other UID, with a spoofable caller name**
  Files: `firestore.rules` (`calls`, `chats`), `call/CallManager.java`, `notifications/DuoShieldMessagingService.java`
  Fix: require a mutual `contacts/{uid}/{partnerUid}` doc (written by both sides via the existing add-contact flow) before either `calls` create or `chats` create is permitted.
  Verify: confirm the existing `AddContactActivity` flow still results in both directions being able to call/chat; confirm a stranger UID with no mutual contact doc is now rejected at the rule.

- [ ] **F21 — "Delete for everyone" has no ownership check — either party can erase the other's message**
  Files: `ChatMediaActivity.java` (`showMessageActionDialog()`, `deleteForEveryone()`), `firestore.rules` (`chats/{chatId}/messages/{msgId}`)
  Fix: gate the UI option on `mine` (same as Edit already is); add matching rule: `allow update: if ... && (!('deletedForAll' in request.resource.data.diff(resource.data).affectedKeys()) || resource.data.sender == request.auth.uid)`.
  Verify: confirm normal own-message deletion still works both client- and rule-side; confirm attempting to delete-for-everyone a message that isn't yours is blocked at both the UI and a direct Firestore write.

- [ ] **F12 — Recipient-side link previews leak IP/view-timestamp to any embedded domain; `og:image` fetch skips SSRF validation entirely**
  Files: `util/LinkPreviewFetcher.java`, `ui/MessageAdapter.java` (`bindLinkPreview()`), `util/GlideHelper.java`
  Fix (minimal first pass): route the `og:image` URL through the existing `isSafeUrl()` check before handing it to Glide. (Full sender-side-unfurl redesign is a larger architectural change — track separately, do not attempt in the same pass as the SSRF gap fix.)
  Verify: confirm normal link previews with safe `og:image` URLs still render; confirm a crafted `og:image` pointing at a private/loopback address is now rejected the same way the HTML fetch already is.

- [ ] **F26 — Disappearing-messages timer is a single global SharedPreferences key, not scoped per conversation**
  Files: `ChatMediaActivity.java` (`getDisappearMs()`, `showDisappearPicker()`, `listenForConvUpdates()`), `util/MessageBuilder.java`, `util/SelfDestructScheduler.java`
  Fix: scope the key to `"disappear_ms_" + conversationId` everywhere it's read/written; have `MessageBuilder` take `conversationId` as an explicit parameter at both call sites (chat screen send, notification quick-reply) instead of reading an ambient global value.
  Verify: confirm switching between two contacts with different timers shows the correct per-conversation value; confirm a partner-sync update for contact B no longer changes what's displayed/applied while viewing contact C; confirm notification quick-reply picks up the correct conversation's timer.

- [ ] **F24 — "Share Contact" sends the card as plaintext, bypassing Signal encryption entirely**
  Files: `ChatMediaActivity.java` (`sendContactCard()` vs `sendMessage()`)
  Fix: route the card string through `SignalCipherHelper.encrypt()` exactly like `sendMessage()`; keep the `contact_card`/`mediaType` tag for special rendering on receipt.
  Verify: confirm contact-card send/receive/render still works end-to-end; confirm the Firestore doc for a contact-card message is now ciphertext with `isEncrypted:true`.

- [ ] **F13 — Notification "Reply" quick-action resolves the wrong (or no) conversation partner**
  Files: `notifications/NotificationStyler.java` (`showMessage()`), `notifications/MessageReplyReceiver.java`
  Fix: add `replyIntent.putExtra("partner_uid", partnerUid)` in `NotificationStyler`; read that extra first in `MessageReplyReceiver`, falling back to the stored preference only if absent.
  Verify: confirm quick-reply from a notification now encrypts to and files under the correct conversation for a multi-contact account; confirm the legacy single-chat fallback (`RestoreFromSeedActivity`'s writer) still works for an account that hasn't sent/received a push yet.

- [ ] **F7 — Duress panic-sync deadline documented as 5s, implemented as 10s**
  Files: `backup/BackupManager.java` (`syncIncrementalSync()`), `security/DuressManager.java` (Javadoc)
  Fix: revert `DEADLINE_MS` to `5_000L` (speed-of-wipe should dominate for a duress path per the feature's own stated purpose) rather than just updating the docstring.
  Verify: confirm the duress-wipe flow (trigger → sync attempt → destructive wipe) still completes and doesn't regress the "no new messages → return immediately" fast path; confirm docstrings in both `BackupManager` and `DuressManager` now agree with the code.

- [ ] **F16 — Duress-wipe's own audit-log write races the DB deletion it's supposed to precede**
  Files: `security/DuressManager.java` (`performLogout()`), `util/SessionLogger.java`
  Fix: add a synchronous/awaited variant of `SessionLogger.log()` (block on the insert via `Future`/`Tasks.await`) and call that from `performLogout()` before `AppDatabase.clearInstance()`.
  Verify: confirm `performLogout()` still completes within a reasonable/bounded time (don't introduce an unbounded block); confirm the log entry is reliably present pre-wipe in a manual test, and confirm no other caller of `SessionLogger.log()` needs migrating (they should keep the existing fire-and-forget behavior).

- [ ] **F25 — Voice-note temp files use `.m4a` but `TempFileCleaner.isTempMediaFile()` only matches `.3gp` — plaintext voice bytes never get swept**
  Files: `util/TempFileCleaner.java` (`isTempMediaFile()`), `ChatMediaActivity.java` (`onVoicePlay()`), `util/VoiceRecorderHelper.java`
  Fix: update the matcher to also accept `.m4a` for `voice_`-prefixed files; add `f.delete()` to the exhausted-retries branch of `uploadVoiceNoteWithRetry()` so a failed upload doesn't leave the plaintext source behind indefinitely.
  Verify: confirm existing `vid_`/`.mp4` matching is untouched; confirm a manually-created `voice_*.m4a` test file is now swept by the cleaner; confirm the failed-upload path still surfaces its existing error/retry UI unchanged.

- [ ] **F11 — B2/TURN credentials compiled as plaintext `BuildConfig` constants, extractable from any APK**
  Files: `app/build.gradle`, `util/B2StorageHelper.java`
  Fix: this is a genuine architecture change (move request-signing server-side via `server/index.js`, minting short-lived per-object URLs the same way `/mintToken` already works) — do not attempt as a quick patch. Track as its own multi-file task; do not fix in the same pass as anything else on this list.
  Verify: after implementation, confirm the client never holds a credential capable of acting on objects it doesn't own; confirm upload/download/delete flows for the current user's own media still work end-to-end.

- [ ] **F5 — Contact and group metadata backed up to Firestore in plaintext (message bodies are already encrypted)**
  Files: `backup/BackupManager.java` (`backupContacts()`, `syncAll()`, `syncIncrementalSync()`)
  Fix: reuse `BackupCryptoHelper.encryptCompressed`/`decryptCompressed` for the `cdoc`/`gdoc` payloads exactly as already done for messages; update `restoreContactsSync()` to decrypt on the way back in.
  Verify: confirm backup and restore round-trip correctly for both contacts and groups after the change; confirm existing plaintext backup docs from before the fix are handled gracefully on restore (migration path, not a hard break for existing users).

- [ ] **F14 — "Wipe & Exit" doesn't sign out of Firebase, and has no confirmation prompt**
  Files: `util/WipeHelper.java`, `ConversationListActivity.java`
  Fix: add `FirebaseAuth.getInstance().signOut()` to `WipeHelper.wipeAll()` (parity with `DuressManager.performLogout()`); add a `MaterialAlertDialogBuilder` confirmation dialog before invoking `wipeAll()`, matching the pattern already used elsewhere (e.g. `CallHistoryActivity`).
  Verify: confirm the wipe still preserves the intentionally-kept contact-list backup (do NOT call `ContactBackupHelper.clearBackup()` — that's the duress-only behavior); confirm the confirmation dialog appears before any destructive action fires, and cancelling it performs no wipe.

- [ ] **F15 — "Export Chat" claims the plaintext PDF is removed after sharing; nothing enforces that**
  Files: `util/ExportHelper.java`
  Fix (minimal, low-risk): soften the dialog copy to accurately describe the actual `TempFileCleaner` cleanup window ("removed automatically within about 20 minutes") rather than promising immediate removal. (A chosen-component `PendingIntent`/`BroadcastReceiver` callback is a larger change — only attempt if the subagent review confirms it's safe and doesn't affect other `createChooser` call sites.)
  Verify: confirm the dialog text change compiles/renders correctly; confirm no functional behavior change beyond the copy (avoids introducing new bugs in a Medium/cosmetic-leaning finding).

- [ ] **F8 — WebRTC calls default to `IceTransportsType.ALL`, exposing both parties' IPs with no relay-only option**
  Files: `call/CallManager.java` (`createPeerConnection()`)
  Fix: add a Settings toggle ("Always Relay Calls") that sets `config.iceTransportsType = PeerConnection.IceTransportsType.RELAY` when enabled; default remains `ALL` unless the user opts in (this is a UX/quality tradeoff, not a silent default flip like F20).
  Verify: confirm calls still connect normally with the toggle off (unchanged behavior); confirm with the toggle on, a call still connects when a TURN server is reachable, and fails gracefully (not silently) if it isn't.

- [ ] **F3 — No group-key rotation path exists for when member-removal ships (not exploitable today — no removal feature exists)**
  Files: `crypto/GroupCipherHelper.java`, `firestore.rules` (`groups/{groupId}/keys/{memberUid}`)
  Fix: **do not implement speculatively.** Leave as a documented TODO in `replit.md`/this roadmap: any future `removeMember`/`kickMember` feature must generate a fresh `groupKey` and redistribute to the remaining members on every membership change, before it ships — not fixed as a standalone item now since there is nothing to patch yet.
  Verify: N/A until member-removal is actually built; re-open this item at that time.

### 🟢 Low / cleanup

- [ ] **F9 — `CallCleanupWorker`'s Firestore query can never succeed (rules reject it) — dead code, no confidentiality impact**
  Files: `call/CallCleanupWorker.java`
  Fix: move the cleanup job server-side (`server/index.js`, Admin SDK bypasses rules, matching the existing `_server_health` pattern) as a periodic `setInterval`; delete the client-side `CallCleanupWorker` and its WorkManager scheduling once the server-side job exists.
  Verify: confirm the server-side job actually deletes stale `calls` docs older than the cutoff in a manual test; confirm no client code still references the removed worker/scheduling call.

- [ ] **F17 — `AppLockManager.coldStart` flag is set but never read anywhere**
  Files: `util/AppLockManager.java`
  Fix: remove the unused field and its stale comment (simplest option — `shouldLock()`'s actual cold-start behavior already works correctly without it).
  Verify: confirm `shouldAutoSignOut()`/`shouldLock()` behavior is byte-for-byte unchanged after removal (it's dead code by definition, so this should be a no-op verification, but confirm anyway since it touches a security-relevant class).

- [ ] **F4 — `SecurePrefs` uses deprecated `MasterKeys`/`MasterKeys.AES256_GCM_SPEC` API**
  Files: `util/SecurePrefs.java`
  Fix: migrate to `MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()`.
  Verify: confirm `SecurePrefs.isAvailable()`/fallback-to-plaintext guard logic is unchanged; confirm existing encrypted prefs written under the old API can still be read after the migration (or confirm a migration path exists if the key alias format changes).

---

## Execution notes

- Findings are addressed strictly in the order above (Critical → High → Medium → Low), one at a time.
- F11 and F3 are explicitly called out as **do-not-quick-patch** items — they need dedicated design work, not a same-pass fix alongside smaller findings.
- Any finding whose fix touches `firestore.rules` must be double-checked against every *other* rule/call-site pairing already reviewed, since a rules file is shared global state — a fix for one finding must not reopen or narrow access needed by an unrelated legitimate flow.
- If a subagent review surfaces a **new** issue while checking a fix, log it as a new finding at the bottom of this file (do not silently fold it into the current item's scope) and triage its priority before continuing down the list.
