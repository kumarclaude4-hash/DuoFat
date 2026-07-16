---
name: DuoShield BaseActivity lockScreenActive
description: What the lockScreenActive static field does and why it must exist
---

## Rule
`BaseActivity.lockScreenActive` is a `public static boolean` that MUST be declared on `BaseActivity`. It is set and cleared by two different classes.

## Details

```
Set to true:   BaseActivity.onStart() — just before startActivity(LockScreenActivity)
Set to false:  LockScreenActivity.unlock() — just before finish()
```

**Why it exists:** When a BaseActivity subclass (e.g. ChatMediaActivity) starts LockScreenActivity, its own `onStart()` may fire again before LockScreenActivity is on screen. Without the guard, `shouldLock()` would still return true and a second LockScreenActivity would be stacked on top of the first.

**How to apply:**
- If you ever remove or rename the field on BaseActivity, you will get a compile error in LockScreenActivity.unlock() (`BaseActivity.lockScreenActive = false`).
- The guard in `onStart()` is `if (!lockScreenActive) { lockScreenActive = true; startActivity(...); }`.
- Do not reset the field in `onResume()` or `onStop()` — only `unlock()` should clear it.
