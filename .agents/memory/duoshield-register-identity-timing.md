---
name: DuoShield registerIdentity timing
description: registerIdentity() must be called inside the onSuccess lambda of account creation, not after the async call returns
---

## Rule
In `SeedPhraseDisplayActivity`, `ContactManager.registerIdentity(userId)` must be invoked **inside** the `onSuccess` callback of whatever async step creates the Firebase Auth account. Calling it in the line immediately after the async call is a timing bug — the UID is not yet available on the main thread.

**Why:** Firebase account creation is asynchronous. If `registerIdentity()` is called synchronously after the `createUser()` invocation (i.e. before `onSuccess` fires), the user document in `identities/{uid}` is never written, making new accounts undiscoverable via `ContactManager.resolveIdentity()`.

**How to apply:** Always chain `registerIdentity(userId)` as the first statement inside `task.addOnSuccessListener(authResult -> { ... })`.
