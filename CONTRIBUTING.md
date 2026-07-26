# Contributing to DuoShield

Thank you for taking the time to contribute. This document covers how to set up your environment, the architecture rules every contributor must follow, and the PR process.

---

## Table of Contents

- [Getting started](#getting-started)
- [Architecture invariants](#architecture-invariants)
- [Commit conventions](#commit-conventions)
- [Pull request process](#pull-request-process)
- [Reporting security issues](#reporting-security-issues)

---

## Getting started

### 1. Clone and set up

```bash
git clone https://github.com/kumarclaude4-hash/DuoFat.git
cd DuoFat

# Write local SDK path
echo "sdk.dir=$ANDROID_SDK_ROOT" > local.properties

# Write a stub google-services.json for local builds
cp app/google-services.json.template app/google-services.json

# Write a stub service-account.json
mkdir -p app/src/main/assets
echo '{"type":"service_account","project_id":"duoshield-8caf1"}' \
  > app/src/main/assets/service-account.json
```

### 2. Compile check

```bash
./gradlew :app:compileDebugJavaWithJavac --no-daemon
```

### 3. Lint

```bash
./gradlew :app:lintDebug --no-daemon --continue
```

Both must pass (`BUILD SUCCESSFUL`) before opening a PR.

---

## Architecture invariants

These rules are non-negotiable. PRs that violate them will not be merged.

### 1. FirebaseCostGuard
Every `addSnapshotListener`, `get()`, `set()`, and `update()` call **must** go through the `FirebaseCostGuard` singleton. Never call Firestore directly.

### 2. One listener per screen
Attach snapshot listeners in `onCreate` / `setupChat`. Detach in `onDestroy`. Never attach inside an `onResume` loop — this leaks listeners.

### 3. DiffUtil in RecyclerView adapters
Use `DiffUtil.calculateDiff` in every `setItems()` method. `notifyDataSetChanged()` is banned — it causes full redraws and jank.

### 4. Background-thread Toasts
`Toast.makeText().show()` is forbidden off the main thread. In helpers (non-Activity classes) use:
```java
new Handler(Looper.getMainLooper()).post(() ->
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show());
```

### 5. BaseActivity for authenticated screens
All authenticated screens **must** extend `BaseActivity` (handles lock check + auto sign-out). The four pre-auth onboarding screens (`SignInActivity`, `DisplayNameActivity`, `RestoreFromSeedActivity`, `SeedPhraseDisplayActivity`) are the only safe exceptions — they extend `AppCompatActivity`.

### 6. Executor lifecycle
Every `ExecutorService` field requires `shutdownNow()` in `onDestroy()`. No exceptions.

### 7. MessageBuilder id field
`MessageBuilder` must always include `"id"` in the Firestore document map. `listenForMessages()` silently skips docs where `id == null`.

### 8. Status downgrade guard
Any write of message `status` must transaction-guard against downgrading `"read"` back to `"delivered"`.

### 9. No Cloud Functions for key operations
All sensitive cryptographic logic runs on-device. Cloud Functions are only used for scheduled sweeps (disappearing messages, media cleanup).

---

## Commit conventions

Use [Conventional Commits](https://www.conventionalcommits.org/):

```
feat: add waveform scrubbing for voice messages
fix: prevent double-tap on send button
chore: bump libsignal to 0.54.2
docs: update architecture diagram
security: add transaction guard for status downgrade
```

**Scopes** (optional, after the type):
`signal`, `crypto`, `db`, `ui`, `calls`, `media`, `notifications`, `backup`, `ci`

---

## Pull request process

1. **Branch** off `main`: `git checkout -b feat/your-feature`
2. **Follow all architecture invariants** above
3. **Run lint + compile** locally before pushing
4. **Fill in the PR template** completely — partial PRs will be asked to resubmit
5. PRs that touch `firestore.rules` require the Firestore rules tests to pass
6. Signed release APKs are built automatically by CI on merge to `main`

### PR checklist (mirrors the template)

- [ ] Debug APK built and smoke-tested on device
- [ ] `FirebaseCostGuard` used for all Firestore calls
- [ ] New `RecyclerView` adapters use `DiffUtil`
- [ ] No `Toast` calls on background threads
- [ ] Any new `ExecutorService` shut down in `onDestroy()`
- [ ] Sensitive new activities extend `BaseActivity`
- [ ] `MessageBuilder` includes `"id"` in every Firestore document

---

## Reporting security issues

**Do not open a public GitHub issue for security vulnerabilities.**

See [SECURITY.md](SECURITY.md) for the responsible disclosure process.
