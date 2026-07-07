---
name: DuoShield backup system improvements
description: What was implemented from the backup scope doc and how each piece fits together
---

# DuoShield Backup System Improvements

Implemented from `attached_assets/DuoShield_Backup_System_-_Final_Scope_of_Improveme_(2)_1782572302804.md`

## Critical items — all complete

### 1. Integrity checksums
- `BackupCryptoHelper.computeChecksum(String plaintext)` → SHA-256 hex of UTF-8 bytes
- `BackupCryptoHelper.verifyChecksum(String plaintext, String expected)` → returns true if match; true (lenient) if expected is null (legacy doc compatibility)
- Stored as a `checksum` field in every Firestore message doc alongside `enc`
- `BackupManager.restoreAllSync()` verifies the checksum after decrypt; mismatches are logged as `E/BackupManager: CHECKSUM MISMATCH for doc <id>` and the doc is **skipped** (not restored)

### 2. Firestore security rules — firestore.rules
Added two new blocks:
```
match /backups/{userId}        — owner read/write; delete=false on all sub-docs (hard-deny)
  match /messages/{msgId}     — owner read/write; delete=false
  match /contacts/{contactId} — owner read/write; delete=false
match /backup_logs/{logId}    — create only (uid must match auth.uid); read=false; update=false; delete=false
```

### 3. Backup key derivation — already correct
`BackupCryptoHelper.deriveBackupKey()` confirmed: `mnemonicToSeed()` → `hkdfSha256(seed, "DUOSHIELD_BACKUP_V1", 32)` ✅

### 4. Seed phrase storage audit — passed + one fix
- No writes to SharedPrefs/Firestore/Room/Log found
- **Fixed**: `SeedPhraseDisplayActivity.btnCopyPhrase` was copying mnemonic to system clipboard → button hidden (`View.GONE`), click listener removed, imports cleaned up

## High priority items — all complete

### 5. Compression
- `BackupCryptoHelper.encryptCompressed(byte[] key, String plaintext)` → GZIP then AES-256-GCM
- `BackupCryptoHelper.decryptCompressed(byte[] key, String blob)` → AES-256-GCM then GUNZIP
- Wire format identical to `encrypt()` (`ivBase64:ciphertextBase64`)
- Caller stores `compressed:true` in Firestore doc so restore knows which path to use
- Old `encrypt()` / `decrypt()` kept **unchanged** for reading legacy docs on restore
- `BackupManager` uses `encryptCompressed()` for all new writes

### 6. Incremental backup
- New `BackupManager.syncIncremental(Context ctx, SyncCallback callback)`
- Reads `last_backup_ts` from `duoshield_prefs` SharedPreferences
- If 0 (first time) → falls through to `syncAll()`
- Otherwise → `messageDao.getMessagesSince(lastTs)` (new query added to MessageDao)
- Updates `last_backup_ts` on success
- `BackupSyncWorker.doWork()` now calls `syncIncremental()` instead of `syncAll()`
- `syncAll()` also sets `last_backup_ts` on success

### 7. Backup monitoring
- `BackupManager.logEvent(uid, event, count, error)` — private helper
- Writes to `backup_logs/{autoId}` with fields: `uid`, `event`, `ts`, `count`, `error?`
- Events fired: `backup_started`, `backup_complete`, `backup_failed`, `backup_size_warning`
- Firestore rules: write-only (no client reads); Firestore Console or Cloud Functions can query

## Medium items — all complete

### 8. Backup size limits
- `SIZE_WARN_LIMIT = 10_000` constant in BackupManager
- Both `syncAll()` and `syncIncremental()` check `total > SIZE_WARN_LIMIT` before starting
- Fires `backup_size_warning` event with count; backup continues (no hard cap)

### 9. Retention policy
- `BackupManager.cleanupOldBackupsAsync(String uid)` — fire-and-forget
- `RETENTION_MS = 90 * 24 * 60 * 60 * 1000` (90 days)
- Queries `backups/{uid}/messages` where `ts < cutoff`, `limit(500)`
- Soft-deletes (sets `isDeleted:true`) via WriteBatch — hard-delete blocked by security rules
- Call this from ConversationListActivity or after syncAll completes to keep backups lean

### 10. Key rotation — SKIPPED (MVP decision)

## Files changed
| File | Change |
|---|---|
| `crypto/BackupCryptoHelper.java` | + computeChecksum, + encryptCompressed, + decryptCompressed |
| `backup/BackupManager.java` | checksum+compressed on writes; compressed-aware restore; incremental sync; monitoring; size check; retention |
| `backup/BackupSyncWorker.java` | syncAll → syncIncremental |
| `db/MessageDao.java` | + getMessagesSince(long sinceTs) |
| `firestore.rules` | + backups + backup_logs rules |
| `ui/SeedPhraseDisplayActivity.java` | btnCopyPhrase hidden; clipboard import removed |

## How to call cleanupOldBackupsAsync
Call it from `ConversationListActivity.onCreate()` after sign-in is confirmed:
```java
FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
if (user != null) BackupManager.cleanupOldBackupsAsync(user.getUid());
```

**Why not wire it in automatically during sync:**
Retention cleanup is a separate concern from sync. Calling it every 24h worker run would be wasteful. Better triggered once on app open or as a monthly scheduled job.
