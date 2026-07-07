---
name: DuoShield signed prekey rotation
description: How the Signal signed pre-key (SPK) is rotated weekly via WorkManager, including the grace-period "prev" key pattern.
---

## Rule
Rotate the signed pre-key every 7 days using a WorkManager daily-check job. Always keep the previous SPK for one full rotation cycle so in-flight sessions can still decrypt.

**Why:** The SPK is the medium-term Curve25519 key in every X3DH bundle. Compromising its private bytes lets an attacker retroactively compute session secrets for all sessions established during its lifetime. Weekly rotation limits exposure to 7 days.

**How to apply:**
- `SignedPreKeyScheduler.schedule(ctx)` is called once from `DuoShieldApp.onCreate()` with `ExistingPeriodicWorkPolicy.KEEP` (1-day interval).
- `SignedPreKeyRotationWorker.doWork()` checks age of current SPK (`current.getTimestamp()`). If age < 7 days it returns `Result.success()` immediately (no-op). If ≥ 7 days it calls `SignalKeyManager.rotateSignedPreKey(ctx)`.
- `rotateSignedPreKey(ctx)`: serialize current SPK → save as `signal_signed_prekey_prev`, generate new Curve25519 pair signed by identity key, assign next monotonic ID from `signal_signed_prekey_next_id` (starts at 2; initial key uses ID=1), write to `signal_signed_prekey`, then fire-and-forget Firestore update of just the `signedPreKey` field.
- `DuoShieldSignalStore.loadSignedPreKey(id)` and `containsSignedPreKey(id)` check current first, then fall back to prev. This means in-flight messages referencing the old SPK ID continue to decrypt for up to 7 days after rotation.
- Firestore update: `bundle.update("signedPreKey", spkMap, "updatedAt", serverTimestamp())` — only the SPK field, no other fields displaced.

## SecurePrefs keys added
- `signal_signed_prekey_prev` — serialised previous SPK (grace period)
- `signal_signed_prekey_next_id` — monotonic counter, seeded to 2 in `generate()`

## Legacy devices
No `signal_signed_prekey_next_id` → falls back to `SIGNED_PREKEY_ID + 1 = 2`. No Room migration needed.
