# DuoShield — UX Review

Scope: user-experience review of the Android client, focused on the
security-adjacent flows where confusing UX directly causes security harm
(key loss, unverified contacts, silent failures). This is separate from the
security audit in the rest of `audit/`.

> Note: `FLAG_SECURE` (screenshot/recording blocking) is **intentionally
> disabled for testing** and is deliberately excluded from this review. Do not
> treat its absence as a defect.

---

## Summary

The app is mature and covers the hard flows most E2EE messengers skip: a
dedicated recovery-phrase screen with a save-confirmation gate, a key
fingerprint / safety-number verification screen with QR scanning, duress unlock
codes, and a PIN gate. The gaps are mostly **consistency, polish, and
persistence of trust state** rather than missing features.

Findings are grouped by severity for UX impact (not security severity).

---

## High impact

### UX-1 — Verification result is ephemeral (no persistent "Verified" state)
`KeyFingerprintActivity` shows an `AlertDialog` on a successful fingerprint
match and clears the `safety_num_changed_<uid>` flag, but there is **no lasting
"Verified ✓" indicator** anywhere afterwards — not on the fingerprint screen,
not in the contact detail, not in the chat header. The user cannot later tell
whether they ever verified a contact, so the verification has little durable
value and users must re-verify from memory.

- Recommendation: persist a per-contact `verified_at` marker on a successful
  match and surface a badge on the fingerprint screen, the contact detail
  screen, and the chat toolbar. Show a distinct "Verification expired / key
  changed" state when the safety number later changes.
- Files: `KeyFingerprintActivity.java`, `ContactDetailActivity.java`,
  `ChatMediaActivity.java`.

### UX-2 — Trust-critical results delivered only as dialogs
Both the match and the mismatch outcomes are transient dialogs with an "OK"
button. A mismatch ("someone may be intercepting your messages") is the single
most important signal in the app and disappears on dismiss.

- Recommendation: render mismatch as a persistent, dismiss-resistant banner in
  the affected conversation until the user re-verifies or explicitly
  acknowledges, rather than a one-shot dialog.

---

## Medium impact

### UX-3 — Seed-phrase screen is visually inconsistent with the app  (FIXED)
`activity_seed_phrase_display.xml` used a pure-black `#000000` background and
hardcoded hex colors, while every other screen uses the `ds_*` design tokens on
the `#191620` background. This made the most important onboarding screen look
like it belonged to a different app.

- Status: **fixed in this pass** — migrated to `@color/ds_*` tokens.

### UX-4 — Dead UI on the seed-phrase screen  (FIXED)
The "Copy" button was permanently `GONE` in code (clipboard exposure of the
mnemonic is intentionally disallowed), yet still present in the layout.

- Status: **fixed in this pass** — the dead Copy button was removed from the
  layout and the QR button now spans the full width. The security rationale
  comment is preserved in the activity.

### UX-5 — Emoji used as icons
`🔒` and `⚠` in the seed layout, and `✅` / `❌` inside verification dialog
copy. These render inconsistently across devices and are read awkwardly by
TalkBack.

- Status: the redundant `🔒` header on the seed screen was replaced with the
  `ic_lock` vector in this pass. The `⚠` QR-dialog warning and the `✅`/`❌`
  in `KeyFingerprintActivity` dialog strings remain and should be replaced with
  vector icons + colored text.

### UX-6 — Setup failure recovery
`SeedPhraseDisplayActivity.deriveAndStore()` surfaces a friendly error on key
upload failure, but a kill between identity-key commit and PIN setup relies on
the `pending_pin_setup_<uid>` flag to recover. Worth an explicit "resume setup"
affordance rather than depending solely on routing logic.

---

## Low impact / polish

- UX-7 — Stale comment: the Continue button is described as "outline green" but
  is rendered in accent purple. (Comment corrected in this pass.)
- UX-8 — Monospace fingerprint `TextView`s have no `contentDescription`;
  TalkBack reads them character-by-character. Add a spoken-friendly description.
- UX-9 — `tvStep` step labels ("Step 1/4 …") are not announced to screen
  readers; consider an `announceForAccessibility` call on each step.
- UX-10 — General: verify touch targets on icon-only buttons (back arrows) meet
  the 48dp minimum; most already do.

---

## Changes made in this pass

1. `activity_seed_phrase_display.xml` — retheme to `ds_*` design tokens, remove
   dead Copy button (QR now full-width), replace emoji lock with `ic_lock`
   vector, correct the stale "outline green" comment.

All other findings above are documented for follow-up and were **not** changed,
to keep this pass low-risk and reviewable.
