---
name: DuoShield architecture rules
description: Non-negotiable rules every agent must follow when editing DuoShield — security, Firestore cost, DiffUtil, Room versioning.
---

# DuoShield Architecture Rules

**Why:** Security app with strict cost controls and no Cloud Functions. Breaking any of these causes either a security hole or runaway Firebase billing.

## Rules

1. **FLAG_SECURE in ALL activities** — `BaseActivity` handles it. Every activity MUST extend `BaseActivity` except: `SignInActivity`, `LockScreenActivity`, `MainActivity` (these set it manually in `onCreate`).

2. **FirebaseCostGuard before every Firestore op** — call `guard.canRead/Write/Delete(n)` and `guard.recordReads/Writes/Deletes(n)`. Class: `com.duoshield.app.util.FirebaseCostGuard`.

3. **One Firestore listener per screen** — attach in `onStart()` or `onCreate()`, store as `ListenerRegistration`, detach in `onStop()`. Never leave orphaned listeners.

4. **Batch deletes only** — use `WriteBatch` (max 450 ops per batch) or `WorkManager`. Never delete in a loop with individual `delete()` calls.

5. **DiffUtil always** — use `adapter.setMessages(list)` / `adapter.setConversations(list)`. Never call `notifyDataSetChanged()` on those adapters. (`updatePinnedIds()` is the one exception — it refreshes a Set, not the full list.)

6. **No Cloud Functions** — all logic is client-side. Firestore security rules enforce access. FCM v1 HTTP API called directly from `ChatMediaActivity` using `service-account.json` + GoogleCredentials.

7. **Room DB version = 10** — any schema change needs `MIGRATION_X_Y` added to `AppDatabase.java` and version bumped. Never use `fallbackToDestructiveMigration()`.

**How to apply:** Before writing any Firestore, Room, or adapter code, run through this list mentally. Flag violations in code review.
