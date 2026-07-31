# Duress PIN Security Plan

**Status:** Design finalized, pending implementation approval — no code has been changed yet.
**Last updated:** 2026-07-31
**Scope:** Android app (`app/`) only. The one piece of account-level state this design needs (§3) lives in Firestore, which the app already talks to directly for everything else. The push notification server (`server/index.js`, hosted on Render) needs no changes and never needs to know duress exists.
**Supersedes:** `attached_assets/duress-pin-security-plan_1785427254488.md` (original draft, left in place for history).

## Revision notes (what changed since the original draft)

The original draft is preserved almost entirely below. Review added:
- A concrete resolution for the WAL/journal disk-remnant concern (reuse existing encrypted storage).
- A specific mechanism for the account-flag timing tradeoff (jittered background delivery).
- A firm answer to the "should the decoy ever self-resolve" open question (no).
- An entirely new section on notification handling — the original plan didn't cover this, and it turned out to be the most likely real-world way the decoy would fail.
- An explicit decision on what happens to messages received while decoy mode is active.
- A documented, consciously-accepted risk in the existing wrong-PIN lockout mechanic.
- A checklist of what the current codebase already satisfies vs. what still needs building, based on reading the actual code rather than assuming.
- Removed the push server from scope entirely: notification suppression is achieved through the existing token-registration lifecycle (tied to real sign-in/sign-out) instead of new duress-aware server logic, so `server/index.js` needs no changes.

## 1. Threat model

Defends against a moderately tech-savvy individual attacker who has physical access to an unlocked or coerced-open device. They may check Settings, check data/network usage, try `adb` or decompile the APK if curious, search the exact text of any error they see, toggle airplane mode, attempt a restore, or retry after time has passed.

This explicitly does **not** defend against forensic labs, chip-off extraction, or state-level actors (Cellebrite/GrayKey-class tooling reads raw device storage below the level any app can defend against). See §11.

## 2. Roles and gating

- **PIN A** — the real unlock PIN.
- **PIN B** — the duress PIN. Entering it looks identical to a normal unlock in the moment, but silently begins the duress sequence below.
- The whole duress-PIN capability is **gated per account** via a remote flag — it is not active by default for every user. An account must be explicitly enrolled before PIN B behaves any differently from a wrong guess. This lives alongside the account's other Firestore data, not on the push server.

## 3. Duress trigger flow

1. PIN B is entered at the lock screen.
2. An **encrypted** duress-state flag is written to local storage immediately — before any other visible state changes, and in a way that survives an unexpected crash mid-sequence. This reuses the app's existing `SecurePrefs` (Android Keystore–backed encrypted storage, already used for PIN hashes elsewhere) rather than introducing a new plaintext preferences file, which also avoids the flag surviving as a recoverable trace in SQLite/XML rewrite artifacts.
3. A duress-active flag is also set on the account's existing Firestore record — not new server infrastructure, just one field alongside everything else already stored there (contacts, keys, tokens). It exists for one specific reason: **local-only state cannot survive an uninstall.** The threat model in §1 already anticipates an attacker attempting a restore; without something outside the device to check, uninstalling the app and restoring the same account fresh (or restoring it on a different device entirely) would perform a real, successful restore instead of continuing the decoy. This flag is what §4 and §8 check to close that gap, and it's also what the sign-out in step 4 relies on to stop notifications (§5) — no separate mechanism needed for either.
4. Like the local flag, this is not sent as an instant, isolated network call — it's queued with a short random delay and retried on reconnect (background work that survives app restarts), so it doesn't show up as a single suspicious network event at the exact moment of PIN entry. This is a deliberate tradeoff: perfect reliability (send synchronously, confirm delivery) and perfect stealth (long delay) pull in opposite directions, so the delay window is kept short enough that losing the signal entirely (e.g., device powered off immediately after) is unlikely, not zero.
5. From this point forward, PIN B decoys as a normal-looking unlock into an empty, freshly-installed-looking app. This behavior is now **permanent and consistent** — identical on the 1st and the 100th time PIN B is entered, with no behavioral tell distinguishing a first use from a later one.

## 4. Decoy shell behavior

- The app presents as a legitimate, PIN-protected app with no account signed in.
- Any attempt to sign in or recover an account inside the decoy fails via a **randomized mix** of failure modes — a generic (but real, findable) error message, a deliberate crash, or an indefinite spinner — rather than one fixed error every time. A single, perfectly consistent failure is itself a fingerprint under repeated testing; varying it is not.
- **This also covers a fresh install.** If the app is uninstalled (wiping the local flag) and reinstalled, or restored on a different device, an attempt to restore the same account checks the account-level flag from §3 and gets the same randomized failure treatment. Reinstalling is not a way around the decoy.
- Error text is drawn from language that matches a real, plausible SDK/network error (the kind that turns up generic, unremarkable results if searched), not custom or duress-specific wording.
- Behavior is identical whether the device is online or offline, defeating an airplane-mode test.
- **The decoy never self-resolves.** It does not time out or repair itself after any amount of time. This was an open question in the original draft; the answer is no, on the reasoning that an automatic change of state after a fixed delay is one more observable behavior a patient attacker could notice ("it fixed itself after a day"), with no corresponding benefit — the real owner already has a way back in (PIN A), so there's no scenario where auto-resolution helps a legitimate user.

### 4a. Deliberate crash notes

- The encrypted duress flag (§3) is written before any crash can occur, never after.
- The exact point/mechanism of the crash is varied, not fixed, for the same fingerprinting reason as the error text above.
- No literal strings such as `"duress"`, `"decoyMode"`, or `"fakeError"` may appear anywhere in the shipped binary — see §9 for what this requires given the current build configuration.
- This entire code path must stay outside any third-party crash or analytics pipeline (Crashlytics, Sentry, etc.). Currently moot — the app integrates no such SDK today — but this must remain true if one is ever added.

## 5. Notification handling during decoy mode

Not covered in the original draft. Without this, the decoy fails the moment anyone messages the real account while the phone is in an attacker's hands — a generic "New message" notification appearing on a phone that's supposedly signed into nothing is a dead giveaway, and it requires no attacker sophistication at all, just ordinary use on the other end of a conversation.

- **Primary suppression is de-registration, not a duress-aware push server.** PIN B causes a real sign-out, and the push server only ever sends to a device currently registered for that account. Today the app registers a device on every sign-in and restore but never removes that registration on sign-out — a pre-existing gap, unrelated to duress. Wiring in the missing removal step, and calling it as part of the sign-out PIN B triggers, means the server has nothing to send to and never attempts delivery. `server/index.js` needs no new logic and never learns duress exists — from its point of view this is indistinguishable from any other signed-out user.
- This is deliberately stronger than suppressing the notification on-device after it arrives: even a silently-dropped notification still causes a brief background data/battery event that would show up in Settings if checked, which the threat model in §1 explicitly anticipates. Never attempting delivery in the first place leaves nothing to find.
- **The de-registration call is jittered/delayed**, for the same reason as the account flag in §3: an instant, isolated network call at the exact moment of PIN entry is itself a correlatable event to a sufficiently attentive attacker watching data usage in real time.
- **Local fallback suppression** covers the gap between PIN B being entered and de-registration actually completing, and any period offline. It reads the same encrypted local flag from §3 — no server involvement.
- **Re-registration on normalization.** Successful normalization (§8) must re-register the device so the real owner keeps receiving real notifications afterward.

## 6. Data continuity during decoy mode

Also not covered in the original draft, and it's a real fork with a real tradeoff, not a detail:

- **Option A (chosen):** local storage and the underlying encrypted session state keep working normally in the background — messages sent during decoy mode are still received and stored, just not surfaced through the UI or a notification. Nothing is lost.
- **Option B (rejected):** local data and session state are genuinely destroyed at the moment of trigger, matching a literal "freshly wiped" story more closely. The cost: any message sent to the account while the phone is in decoy mode is very likely gone permanently — the encryption scheme here builds up session state message by message, and unlike the long-term identity key (confirmed below to be recoverable from the seed phrase), that per-conversation session state has no seed-phrase-based recovery. Rebuilding a session after the fact can also itself be a small, separately visible event.

Option A was chosen because the stated threat model (§1) already excludes attackers capable of real forensic extraction — against that tier, encrypted-at-rest data sitting quietly on the device isn't something the attacker can get into anyway, so Option B trades away real, permanent data loss for a forensic guarantee this design doesn't need.

Confirmed as already safe regardless of which option: the app's cryptographic identity key is derived deterministically from the seed phrase (HKDF-SHA256 over the seed). Restoring the same account later reproduces the exact same identity, so normalization does not trigger a "safety number changed" warning on any contact's screen.

Worth deciding at implementation time, and noted here as a related but distinct channel: if the coercive party is themselves a contact rather than someone physically holding the device, they never need to touch the phone at all — they'd notice if delivery/read receipts stopped updating. Whether receipts also keep functioning normally in the background during decoy mode should be decided alongside Option A, for the same reason Option A was chosen.

## 7. Wrong-PIN attempt lockout (accepted existing behavior)

The current app already has a mechanic that overlaps with this feature: **five consecutive wrong PIN guesses trigger the same duress logout function that deliberately entering PIN B triggers.** This is true today, independent of this plan.

This was reviewed explicitly during design. The tradeoff:
- It means someone under genuine, panicked coercion doesn't need to precisely recall the exact secondary code — failing to enter the real PIN correctly a few times has the same protective effect, which can matter when panic impairs memory.
- It also means the real owner can trigger the full sequence by accident — a typo, a child handling the phone, five failed unlocks for any innocent reason — resulting in a false duress signal (both flags in §3) and a full recovery flow to undo.

**Decision: keep the current behavior as-is.** The false-positive risk is consciously accepted rather than splitting this into a separate, softer lockout. No change planned here.

## 8. Normalization flow (recovery)

1. PIN A does **not** unlock anything directly once an account is in duress mode. It only signals "the real owner is back."
2. That signal clears both the local flag and the account-level Firestore flag from §3, then force-routes into a mandatory, non-skippable re-authentication screen.
3. Re-authentication requires the seed phrase and the account ID together, checked as **one atomic operation** with a **single generic failure message** — never a distinct "wrong seed" vs. "wrong ID" error, which would otherwise let an attacker narrow down which half they'd gotten right. The app's existing restore flow already asks for both seed phrase and account ID as a two-factor check; implementation should confirm the failure messaging is already collapsed to one generic result, and tighten it if not.
4. Only after that succeeds does the user set **brand-new PIN A and PIN B** — both are rotated, even though only PIN B was ever exposed, on the assumption that anything visible during the same coercion event should be treated as compromised together.
5. There is no intermediate state anywhere in this sequence where data is unlocked but not fully protected by either the decoy or the new PINs.
6. As noted in §5, this flow must also re-register the device for notifications.

## 9. Binary and account-data hygiene

- The feature is enabled per-account via a **remote flag** (the same Firestore-backed account record as §3 — not the push server), not shipped active for every install by default.
- Making the duress-handling code **entirely absent** from the binary for non-enrolled accounts (rather than present-but-inactive) would require dynamic feature delivery or per-account custom builds — a significant infrastructure investment. Given the threat model in §1 already excludes attackers capable of deep static analysis against a forensic-lab budget, this is not recommended as a v1 requirement; the remote flag plus the renaming below is the practical target.
- The release build already enables `minifyEnabled` and `shrinkResources` with ProGuard configured — a reasonable foundation, but this only renames classes/methods, it does **not** obfuscate string literals. The current codebase uses literal identifiers like the `DuressManager` class name and preference keys containing the word "duress" — these would appear in plain text in a decompiled build regardless of the existing ProGuard configuration. Implementation needs a deliberate renaming pass across these identifiers to neutral names as part of this work, not just relying on existing build settings.

## 10. Forensic hygiene checklist — status

| Requirement | Status |
|---|---|
| No plaintext duress state on disk | Not yet wired — route through existing `SecurePrefs`, no new infrastructure needed |
| Duress-related files excluded from device backups | **Already satisfied**, and more broadly than required — the app already disables Android backups entirely (`allowBackup="false"` plus a data-extraction-rules file) |
| Nothing duress-related sent to third-party analytics/crash tools | Currently moot — no such SDK is integrated in the app today. Must stay true if one is added later |
| Binary obfuscated/stripped of descriptive strings | Partially in place — release build already minifies and shrinks, but string literals need a dedicated renaming pass (§9); not automatic from current settings |
| Constant-time comparison across wrong-PIN / PIN B / PIN A | **Not currently true.** The lock screen's check short-circuits so that matching PIN B runs one slow hash comparison while matching PIN A or a wrong guess runs two — a measurable, real timing difference today. Needs fixing so both comparisons always run and take equal time regardless of outcome |
| Reused, plausible error text | Designed (§4), not yet implemented |
| Identical online/offline behavior | Designed (§4), not yet implemented |
| Account-flag update delayed/batched, not an isolated obvious event | Designed (§3) via jittered background delivery, not yet implemented |
| No local data/network-usage trace when duress-flagged | Addressed by de-registering the push token instead of relying on the push server to gate anything (§5), not yet implemented |
| Notification registration tied to signed-in state | Confirmed gap — registration happens on sign-in, nothing currently removes it on sign-out. Needs wiring (§5) |
| Reinstall/restore-to-new-device can't bypass the decoy | Addressed by the account-level flag (§3, §4), not yet implemented |

## 11. Non-goals

This design is not intended to resist forensic-lab tooling, chip-off extraction, or a state-level adversary — those read raw device storage in ways no app-level design can prevent. It is scoped to a individual attacker with physical access and moderate technical skill, as defined in §1.

## 12. Implementation scope summary

**Android app (`app/`) — this is the entire scope; no push-server changes needed:**
- `DuressManager.java`, `PinManager.java` — constant-time fix; route the local duress flag through `SecurePrefs`; read/write the account-level Firestore flag (set on trigger, cleared on normalization); identifier renaming (§9)
- `LockScreenActivity.java` — constant-time fix (always run both hash checks, regardless of outcome); integration point for randomized decoy failure behavior
- New decoy-shell screens — fake sign-in/recovery UI with randomized failure injection (§4), triggered by either the local flag (still-installed case) or the account-level flag (fresh-install/restore case)
- `SplashActivity.java` and app routing — must check the **account-level Firestore flag**, not just local state, on any fresh install or restore, since local state is trivially cleared by an uninstall
- `RestoreFromSeedActivity.java` — verify/tighten seed+ID failure messaging to a single generic error; wire into the mandatory re-auth step in §8; check the account-level flag before allowing a real restore to proceed
- `NotificationHelper` / `NotificationStyler` / `DuoShieldMessagingService` — add local fallback suppression (§5)
- `FcmTokenHelper.java` — wire the existing (but currently uncalled) unregister step into sign-out, including duress-triggered sign-out, jittered per §5; confirm re-registration on normalization
- `proguard-rules.pro` / relevant build config — support the renaming pass in §9

**Push server (`server/index.js`):** no changes. It keeps sending to whatever token is currently registered for an account, and nothing if none is registered — exactly what it does today for any signed-out user. It never needs to know duress exists.
