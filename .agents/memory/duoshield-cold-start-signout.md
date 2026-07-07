---
name: DuoShield cold-start auto sign-out prevention
description: AppLockManager skips shouldAutoSignOut on the first foreground after process creation
---

## Rule
`AppLockManager.shouldAutoSignOut()` must return `false` on the very first call after the process is created (cold start). A static `AtomicBoolean coldStart = new AtomicBoolean(true)` guards this — `getAndSet(false)` in `shouldAutoSignOut()` makes the first call always skip the check.

**Why:** When the user kills the app and reopens it, the old `bgTs` (background timestamp from before the kill) is stale. Evaluating it against the auto sign-out threshold incorrectly logs the user out even though they just intentionally opened the app. The `coldStart` flag is process-local (static), so it resets to `true` on every fresh process creation — which is exactly when the stale bgTs problem occurs.

**How to apply:** The flag is already in `AppLockManager`. Do not remove it or move the `getAndSet(false)` call. The PIN lock (`shouldLock()`) is intentionally NOT skipped on cold start — it uses the same bgTs but that behaviour (showing the lock screen after a timeout even on restart) is desired.
