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

## Confirmed OPEN (lower priority / architectural) — re-verified 2026-07-29
- **F2** Identity front-running window during new-account onboarding
- **F3** No group key rotation on member removal (no removal feature yet)
- **F5** Plaintext contact/group metadata in cloud backups (displayName etc. stored unencrypted in `backups/{uid}/contacts`)
- **F6** No contact-consent gate (chats created server-side via /createChat; calls still possible without consent)
- **F8** No relay-only calling option — `CallManager` sets `iceTransportsType = ALL`, not RELAY; IP still exposed to call partner
- **F12** Link preview auto-fetch leaks user IP to target server (LinkPreviewFetcher/LinkPreviewHelper still fetch directly)
- **F15** Plaintext chat-export PDF persists in storage (ExportHelper)
- **F34** RestoreFromSeedActivity.migrateOldUidViaServer() exists and looks functional — re-audit for correctness before closing (not verified line-by-line this pass)

## Implementation Rules
1. Every code change cross-checked by a review subagent — non-negotiable
2. Compile (:app:compileDebugJavaWithJavac) after each cluster
3. Never change layout XML IDs
4. Firestore rule changes should be deployed: `firebase deploy --only firestore:rules`
5. Work one cluster at a time

**Why:** Dense codebase; DuressManager/Signal/Firestore changes have wide side effects.
