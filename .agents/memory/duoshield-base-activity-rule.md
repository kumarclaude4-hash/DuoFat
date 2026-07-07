---
name: DuoShield BaseActivity security rule
description: All sensitive activities must extend BaseActivity; only 3 exceptions are acceptable
---

## Rule
Every Activity that shows user data, settings, crypto material, or session history MUST extend `BaseActivity`. Extending `AppCompatActivity` directly skips the 3-minute background lock check in `BaseActivity.onStart()`.

## Allowed exceptions (by design)
| Activity | Why AppCompatActivity is OK |
|---|---|
| `BaseActivity` itself | It IS the base |
| `LockScreenActivity` | It IS the lock screen — must not lock itself |
| `SignInActivity` | Pre-auth — no user data; no session to protect |
| `MainActivity` | Entry-point router; navigates away before showing any data; comment in code explains |

## What was found (B-4 — 2026-06-15)
Three activities extended `AppCompatActivity` instead of `BaseActivity`:
- `SessionLogActivity` — shows all sign-in/sign-out events
- `SeedPhraseDisplayActivity` — shows the 12-word mnemonic (most sensitive screen)
- `RestoreFromSeedActivity` — accepts mnemonic input

All three were fixed by changing `extends AppCompatActivity` → `extends BaseActivity` and updating the import.

**How to apply:** Whenever adding a new Activity, check its superclass. If it shows anything after the user has logged in, it must extend `BaseActivity`.

**Why:** `BaseActivity.onStart()` calls `AppLockManager.shouldLock()` → starts `LockScreenActivity` if the app has been in the background for >3 minutes. Without this, a thief who picks up an unlocked phone can navigate directly to seed phrase display or session log by launching those activities.
