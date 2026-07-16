---
name: DuoShield duress wipe routing guard
description: How the duress_wipe_in_progress flag prevents SignInActivity bouncing back to the chat screen during a wipe
---

# Duress Wipe Routing Guard

## The Rule
`DuressManager.performLogout()` writes `duress_wipe_in_progress = true` (synchronous `.commit()`) to `duoshield_prefs` **before** starting `SignInActivity`. All three auto-route entry points check this flag and skip routing if it is set.

## Entry Points That Must Check the Flag
- `SignInActivity.onCreate()` — primary check; duress route lands here directly
- `SignInActivity.onStart()` — secondary check; same activity, same guard needed
- `SplashActivity.navigate()` — cold-start path: routes via `AuthStateListener` to `MainActivity`
- `MainActivity.proceedAfterPermission()` — launched from SplashActivity; guards `route()`

## Ordering in DuressManager.performLogout()
1. Write flag (`commit()`) → launch `SignInActivity`
2. Background thread: panic sync → `SessionLogger.logSync(SIGN_OUT)` → `clearInstance()` → delete DB → wipe SecurePrefs → wipe all prefs files (this also wipes the flag) → `FirebaseAuth.signOut()`
3. Explicit `remove("duress_wipe_in_progress")` as safety net after signOut, in case step 2's full-clear failed

**Why the explicit remove?** The `.clear().commit()` on `duoshield_prefs` in step 3d already removes the flag. The explicit remove at the end is a safety net: if the full prefs wipe failed mid-way (rare but possible on low-storage devices), SignInActivity would remain permanently blocked on the welcome screen. The explicit remove ensures the guard is always lifted.

## logSync() — Synchronous Session Log Write
`SessionLogger.logSync()` writes directly on the calling thread (blocks). Used inside the duress background thread **before** `AppDatabase.clearInstance()` to guarantee ordering (F16 fix). The async `SessionLogger.log()` uses a separate `ExecutorService` and would race the DB delete.

## Session Log Display (F35 fix)
- Old duress logouts wrote `DURESS_LOGOUT` event type → rendered as "Duress logout" (red)
- Fixed: `performLogout()` now writes `SIGN_OUT` via `logSync()`
- Legacy `DURESS_LOGOUT` rows (from before this fix) are rendered as "Signed out" (grey) in `SessionLogActivity` — same as normal `SIGN_OUT`

**Why:** Rendering a different label for duress vs normal logout defeats plausible deniability — an adversary with access to Settings can distinguish the two.
