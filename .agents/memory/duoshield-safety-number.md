---
name: DuoShield safety number banner
description: How the identity-key-change warning banner works in ChatMediaActivity
---

## Rule
When the partner's Signal identity key changes, show a persistent amber banner in ChatMediaActivity with VERIFY → KeyFingerprintActivity and ✕ dismiss (session-only).

## How it works

### Trigger (`DuoShieldSignalStore.saveIdentity()`)
When `existing != null && !existing.equals(incoming)` (key changed, not new TOFU):
1. Writes `safety_num_changed_<address.getName()> = true` to `duoshield_prefs` (plain SharedPreferences).
2. Also writes updated `signal_partner_identity_key` to SecurePrefs so `KeyFingerprintActivity` shows the new fingerprint.

### UI (`activity_chat_media.xml`)
`@id/safetyNumberBanner` — LinearLayout between `disappearTimerBanner` and `RecyclerView`. `visibility="gone"` by default. Contains text, `@id/btnVerifySafetyNumber` (clears flag permanently), `@id/btnDismissSafetyNumber` (hides for session only).

### `checkSafetyNumberBanner()` in `ChatMediaActivity`
- Reads `duoshield_prefs.getBoolean("safety_num_changed_" + partnerUid, false)`.
- If true → VISIBLE; wires click listeners.
- If false → GONE.
- Called from: `setupChat()`, `onResume()`, `ensureSignalSession().onEstablished()` via `runOnUiThread`.

**Why:** TOFU (trust-on-first-use) is the design, but mid-session key changes are a red flag (partner re-installed, or MITM). The user must be told so they can verify out-of-band.

**How to apply:** Never call `saveIdentity()` directly for display; always let the flag drive the banner. Never clear the flag except via the VERIFY button click.
