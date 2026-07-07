---
name: DuoShield FLAG_SECURE — COMPLETE
description: FLAG_SECURE added to all activities in v1.3; no longer deferred
---

## Status — DONE (v1.3)

`getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE)` is now active on every activity.

## Coverage

- `BaseActivity.onCreate()` — covers all 9 subclasses automatically (ChatMediaActivity, GroupChatActivity, ConversationListActivity, SettingsActivity, AddContactActivity, CreateGroupActivity, FakeChatsActivity, …)
- Individually added (extend AppCompatActivity, not BaseActivity):
  - `LockScreenActivity.onCreate()`
  - `SignInActivity.onCreate()`
  - `MainActivity.onCreate()`
  - `RestoreFromSeedActivity.onCreate()`
  - `SessionLogActivity.onCreate()`
  - `SeedPhraseDisplayActivity.onCreate()`

**Why:** Prevents the app from appearing in the recent-apps thumbnail, blocks Android screen recording, and blocks the built-in screenshot mechanism — essential for a secure messenger.

**How to apply:** No further action needed. If a new Activity is added that extends BaseActivity, it inherits FLAG_SECURE automatically. If it extends AppCompatActivity directly, add `getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE)` in its `onCreate()`.
