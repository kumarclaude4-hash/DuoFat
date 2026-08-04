---
name: DuoShield FLAG_SECURE — REMOVED
description: FLAG_SECURE removed from all screens 2026-08-03; screenshots enabled everywhere
---

## Status — REMOVED (2026-08-03)

`FLAG_SECURE` has been fully removed from every activity. Screenshots and screen recording are allowed on all screens with no exceptions.

## What was changed

- `BaseActivity.onCreate()` — now always calls `clearFlags(FLAG_SECURE)` (removed preference-based conditional)
- `MainActivity.onCreate()` — same, always clears
- `LockScreenActivity.onCreate()` — same, always clears
- `RestoreFromSeedActivity.onCreate()` — hardcoded `addFlags` removed
- `SeedPhraseDisplayActivity.onCreate()` — hardcoded `addFlags` removed
- `SetupPinActivity.onCreate()` — hardcoded `addFlags` removed
- `SecurityPrivacySettingsActivity.applyScreenshotFlag()` — now always clears, never adds

**Why:** User explicitly requested screenshots enabled across full app with no exceptions.

**How to apply:** Do NOT re-add FLAG_SECURE to any screen without an explicit user request. If a new Activity is added, no FLAG_SECURE action is needed — BaseActivity subclasses inherit the clearFlags() call automatically.
