---
name: DuoShield security review plan
description: Status map of all 44 findings from two security review reports; which are fixed vs open; remediation clusters
---

# DuoShield Security Review — Finding Status

Source reports: `attached_assets/DuoShield_Code_Review_Report_(6)_1783403471815.md` (13 rounds, 41 findings) and `attached_assets/DuoShield_Security_Code_Review_Report_1783403471815.md` (round 14, findings 42-44).

## Confirmed FIXED (verified in live code)
- **F4** Deprecated MasterKeys API — SecurePrefs uses MasterKey.Builder (security-crypto 1.1.0-alpha06)
- **F7** Duress panic-sync deadline — BackupManager DEADLINE_MS=10_000; all comments now say 10 s
- **F10** (partial) B2CleanupWorker has isOwnedB2Path() check before delete
- **F13** NotificationStyler — per-conv PendingIntent request codes; replyIntent includes partner_uid; MessageReplyReceiver uses intent extra first
- **F14** WipeHelper.wipeAll() calls FirebaseAuth.signOut()
- **F16** SessionLogger race — logSync() before wipe in DuressManager
- **F17** Dead coldStart AtomicBoolean removed from AppLockManager
- **F18** Signal thread safety — SESSION_LOCKS ConcurrentHashMap in SignalCipherHelper
- **F19** Message create rule enforces sender==auth.uid + isEncrypted==true (Firestore rules)
- **F20** FLAG_SECURE implemented in BaseActivity and LockScreenActivity
- **F21** Message delete rule scoped: sender-only OR expired expiresAt (Firestore rules)
- **F22** KeyFingerprintActivity uses per-address identity key slot
- **F23** VERIFY clears safety_num_changed_<uid> only after confirmed match
- **F24** sendContactCard() encrypted via Signal; isEncrypted:true in Firestore
- **F25** TempFileCleaner cleans .m4a alongside .3gp for voice_ prefix
- **F26** disappear_ms scoped per-conversation (disappear_ms_<convId>) in ChatMediaActivity, MessageBuilder, SelfDestructScheduler
- **F27** Group key substitution — groups/{groupId}/keys/{memberUid} write restricted to createdBy
- **F28** Group message spoofing — sender==auth.uid + isEncrypted==true enforced on create
- **F29/F44** Identity hijacking — identities/{userId} rule checks auth.uid==userId in path AND payload
- **F30** SignInActivity auto-route race — duress_wipe_in_progress guard in SplashActivity/SignInActivity/MainActivity
- **F31** Biometric onSuccess resets pin_fail_count
- **F32** WipeHelper.wipeAll() clears duoshield_security_prefs
- **F33** ConversationMetaUpdater.update() sends "🔐 New message" (not plaintext)
- **F35** DuressManager — duress_wipe_in_progress flag; logSync(SIGN_OUT) before clearInstance()
- **F36** B2StorageHelper 403 error uses getMaskedKeyId()
- **F38** Chat update rule blocks spoofing partner's typing_/online_/lastSeen_/unread_ fields; participants immutable; otherUid() helper function added (Firestore rules)
- **F39** pinMessage() stores only {id} in Firestore pinnedMessages[] — no plaintext preview
- **F40** TextStyleHelper.java deleted (zero callers)
- **F41** ReactMessageHelper.java + TypingThrottle.java deleted (zero callers)
- **F43** SelfDestructWorker dead TTL block removed; MessageDao.deleteOlderThan() removed; stale indexes pruned from firestore.indexes.json
- **F20** (re-verified 2026-07-29) FLAG_SECURE active on all activities — global in BaseActivity/MainActivity/LockScreenActivity, plus RestoreFromSeedActivity/SeedPhraseDisplayActivity keep it unconditionally
- **F1** (re-verified 2026-07-29) Release keystore no longer tracked in git — removed from repo, `*.keystore` gitignored
- **F9** (re-verified 2026-07-29) CallCleanupWorker is wired via `scheduleIfNeeded()` in DuoShieldApp — not dead code
- **F11** (re-verified 2026-07-29, partial) TURN credentials fetched server-side (`/turnCredentials`, TURN_TOKEN_ID/TURN_API_TOKEN never reach the device); B2 keys no longer in BuildConfig — media now routes through the Cloudflare Worker (WORKER_URL/WORKER_SECRET) instead of raw B2 SigV4 signing on-device

- **F21** "Delete for everyone" gated on `mine` in UI; Firestore message update rule restricts `deletedForAll` to sender only — ⚠️ rules file updated but NOT yet deployed (needs `firebase deploy --only firestore:rules`)
- **F37** MediaStoreWipeHelper.java: recordUri() tracks gallery saves; wipeAll() deletes them; called from WipeHelper.wipeAll() and DuressManager.performLogout() before prefs clear
- **F42** MODIFIED listener + deleteForEveryone() show "⛔ Message deleted" tombstone via adapter.updateMessage() + messageDao().updateText() instead of silent removal
- **F2** (verified 2026-07-29) Identity front-running closed — server `/mintToken` claims `identities/{userId}` inside a Firestore transaction before minting the custom token; no window between "account doesn't exist" and "identity bound"
- **F3** (verified 2026-07-29) Group key rotation on member removal shipped — `GroupChatActivity.showGroupInfoSheet()`/`removeMemberAndRotateKey()` + server `/removeGroupMember` (creator-only, atomic array-remove + key-doc delete) + fresh AES key redistributed to remaining members via Signal
- **F5** (verified 2026-07-29) Contact/group backup metadata (displayName, group name) AES-256-GCM encrypted via `BackupManager.encMeta()`/`decMeta()` before leaving the device; legacy plaintext docs still decode via fallback
- **F8** (verified 2026-07-29) Relay-only calling shipped — `switchRelayOnlyCalls` in SecurityPrivacySettingsActivity toggles `relay_only_calls_enabled`; CallManager reads it and sets `IceTransportsType.RELAY` vs `ALL`
- **F12** (verified 2026-07-29) Link preview fetch proxied server-side — `LinkPreviewFetcher` posts to push-server `/linkPreview` with a Firebase ID token; client/target never talk directly
- **F15** (verified 2026-07-29) Chat export switched from PDF to plaintext `.txt` with a mandatory "unencrypted, will be removed" warning dialog (`ExportHelper`); `TempFileCleaner` deletes `duoshield_export_*` files after 5 min

## Confirmed OPEN (lower priority / architectural)
- **F6** No contact-consent gate (chats created server-side via /createChat; calls still possible without consent)
- **F34** RestoreFromSeedActivity.migrateOldUidViaServer() exists and looks functional — re-audit for correctness before closing (not verified line-by-line this pass)

**Note:** this file previously lagged the actual code — F2/F3/F5/F8/F12/F15 were already fixed and tagged in-code (`// F<n> fix`) before this memory entry was updated. When in doubt, grep the codebase for the finding tag rather than trusting this list at face value.

## Implementation Rules
1. Every code change cross-checked by a review subagent — non-negotiable
2. Compile (:app:compileDebugJavaWithJavac) after each cluster
3. Never change layout XML IDs
4. Firestore rule changes should be deployed: `firebase deploy --only firestore:rules`
5. Work one cluster at a time

**Why:** Dense codebase; DuressManager/Signal/Firestore changes have wide side effects.
