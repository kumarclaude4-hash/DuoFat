---
name: DuoShield BaseActivity auto sign-out fix
description: Firebase-null path in BaseActivity.onStart() must NOT clear bgTs
---

# BaseActivity auto sign-out / lock fix

**Rule:** When `FirebaseAuth.getCurrentUser()` returns `null` and `explicit_signout` flag is NOT set (transient Firebase restoration), do NOT call `AppLockManager.onAppForegrounded(this)`.

**Why:** Calling `onAppForegrounded()` clears `bgTs` (the background timestamp). If Firebase takes a moment to restore auth on startup, bgTs gets cleared, and the auto sign-out / lock timer fires incorrectly afterward (always reads 0 elapsed time → never signs out when it should).

**How to apply:**
- In the Firebase-null early-return path in `BaseActivity.onStart()`, just `return` without clearing bgTs.
- `bgTs` is only cleared in the `else` branch of `shouldLock()` (i.e., when we confirm the user is actively present and lock is not needed).
- `LockScreenActivity.unlock()` already calls `onAppForegrounded()` after a successful PIN/biometric — this is the correct and only other clearing point.
