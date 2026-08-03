---
name: DuoShield duress-PIN finalized design (closed decisions)
description: Authoritative decisions after two rounds of user Q&A — decoy-shell direction is dropped for good; read before touching anything duress-related
---

# Duress/Decoy Feature — Final, Closed Design

## Do not resume the decoy-shell direction
The decoy-shell approach (fake second identity, decoy chats, randomized failure modes,
`docs/DURESS_PIN_SECURITY_PLAN.md` §3-9) was fully **dropped**. If asked to "finish the
duress/decoy feature," do NOT resume that implementation. `docs/DURESS_PIN_SECURITY_PLAN.md`
is now historical only.

**Why it was dropped — two unbuildable problems:**
- PIN-A re-entry as a "normalization" signal can't work: the real PIN's hash is wiped
  during the trigger itself, so there is nothing left to check a re-entered PIN against.
- "Messages keep arriving silently in the background during decoy mode" conflicts with
  the existing full local-wipe-on-trigger behavior.

## Current final design (verified live in code, matches `git show origin/main` history)
- Only an exact duress-PIN match triggers duress. No wrong-guess-count fallback — it
  caused false-positive full-account lockouts from typos/kids, with no compensating
  upside once there's no decoy state to protect.
- On trigger: full local wipe (unchanged), then routes to the **ordinary sign-in screen**
  (`SignInActivity`) — no decoy shell of any kind. A PIN screen only exists because a
  session exists, so anything other than the normal signed-out state is itself detectable.
- Reinstall-survival: `accountLock/{uid}` — a separate collection from `users/{uid}`
  (which is broadly readable). Shape: `{ locked, lockedAt }`. Owner-write-only via a
  jittered worker (`AccountLockWorker`), owner-read-only during restore, checked
  alongside the identity read for timing parity (`RestoreFromSeedActivity`).
- **Clearing an accountLock is manual-only, forever** — Firebase console/admin script.
  No self-service unlock exists or is planned. Deliberate permanent simplification.
- Secondary-PIN setup UI is hidden entirely unless `duressEligibility/{accountId}.eligible`
  (admin-set, account-readable only) — non-enrolled accounts never render any trace of it.
- Threat model is explicitly **acute coercion only** (attacker has no prior access, is
  demanding the PIN in the moment). Chronic/prior-access attackers are out of scope —
  this is why hiding the secondary-PIN setup option is considered safe rather than theater.
- Known accepted residual gap: decompiling the APK still reveals the feature exists
  (class/layout/string names). A renaming pass across ~20+ files would close it;
  explicitly deferred, standalone, not blocking.
- Also part of this plan (already implemented): invite-only waitlist for new-account
  creation (`WaitlistHelper`, `RequestAccessActivity`), and a universal mandatory PIN on
  all new accounts (`SetupPinActivity`).

## Deleted as part of this closure
`NormalizationActivity` (+ its layout) and the old `PinManager` wrong-guess-count logic
were removed entirely — they only existed to support the dropped decoy-shell direction.

## How to apply
Treat this file, not `docs/DURESS_PIN_SECURITY_PLAN.md`, as the source of truth for
duress/decoy scope. Any future duress-related request should be checked against this
list before writing code.
