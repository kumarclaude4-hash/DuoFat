---
name: DuoShield contacts restore bug
description: ContactBackupHelper.restoreIfNeeded() was never called after login; fixed by hooking it in ConversationListActivity.onCreate()
---

## Rule
`ContactBackupHelper.restoreIfNeeded(ctx, myUid)` must be called on a background thread in `ConversationListActivity.onCreate()` immediately after `myUid` and `executor` are set up. Without this call, contacts backed up during Wipe & Exit are never restored.

**Why:** WipeHelper.wipeAll() backs up contacts to `duoshield_contacts_bak` SharedPreferences before wiping Room DB, but the restore side was never wired to any launch path — contacts were permanently lost after any Wipe & Exit.

**How to apply:**
- The fix is in `ConversationListActivity.onCreate()` — executor.execute with `ContactBackupHelper.restoreIfNeeded(getApplicationContext(), uidForRestore)` right after executor/localDb init.
- `unpairDevice()` in `SettingsActivity` must also call `ContactBackupHelper.backup()` before `AppDatabase.clearInstance()` — it previously skipped the backup step that WipeHelper does.
- `restoreIfNeeded()` is a no-op when backup is absent or UID mismatch, so it's safe to call on every app launch.
