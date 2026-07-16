---
name: DuoShield SplashActivity auth listener
description: SplashActivity must use addAuthStateListener not getCurrentUser() for cold-start navigation
---

## Rule
`SplashActivity.navigate()` must call `FirebaseAuth.getInstance().addAuthStateListener(...)` to determine the auth state on cold start — never rely on `getCurrentUser()` alone.

**Why:** `FirebaseAuth.getCurrentUser()` returns `null` during the async initialisation window on first launch (cold start from killed state), causing the app to silently route unauthenticated users through the sign-in flow even when a valid session exists. The state listener fires once the SDK has loaded the persisted token, giving the correct value.

**How to apply:** In `SplashActivity.navigate()`, remove the immediate `getCurrentUser()` branch and instead add a one-shot `AuthStateListener` that calls `listener.remove()` after firing. Route to `MainActivity` if user != null, else to `SignInActivity`.
