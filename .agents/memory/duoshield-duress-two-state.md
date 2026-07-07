---
name: DuoShield Duress PIN two-state UI
description: Entire duress section is GONE once a PIN is saved; clearing app PIN requires duress PIN verification first
---

## Rule — Section visibility
`refreshDuressState()` controls `layoutDuressSection` (the outer wrapper that contains the divider, section header, toggle card, and form). When `DuressManager.hasDuressPin()` is true → `layoutDuressSection.setVisibility(GONE)`. No trace of the duress feature is visible.

The section becomes visible again only when `hasDuressPin()` returns false — which only happens after the app PIN is cleared (clearing the app PIN also clears the duress PIN).

**Why:** The user requires plausible deniability — once the duress PIN is set there must be no visible evidence that the feature exists. The previous design showed a toggle that could be flipped off without any credential check.

## Rule — Clearing the app PIN when duress is active
`confirmClearPin()` must gate on duress PIN verification BEFORE the app PIN verification, using `promptDuressPin(onVerified)`. This prevents someone who only knows the app PIN from silently removing the duress PIN.

Flow: duress verification dialog → (on success) → app PIN verification dialog → `doClearPin()`.

## Rule — Toggle behaviour
The switch toggle only controls `layoutDuressContent` visibility (the PIN entry form). Once a PIN is saved, `refreshDuressState()` hides the entire `layoutDuressSection` so the toggle is unreachable. The toggle's OFF listener does NOT call `clearDuressPin()` — it only resets the EditText (since the form-state toggle can only be seen/used when no duress PIN is saved).

## How to apply
Call `refreshDuressState()` in:
1. `onCreate()` after toggle setup
2. `saveDuressPin()` success path (hides the section immediately after saving)
3. `doClearPin()` at the end (shows the section again after both PINs are cleared)
