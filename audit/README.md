# DuoShield Security Audit

This folder is the **single source of truth** for the DuoShield security assessment.
It is written so that any security engineer can pick up the audit **cold** — with no
prior conversation context — and continue exactly where the previous session stopped.

---

## START HERE (new engineer, cold start)

1. **Read (≈15 min), in order:** `README.md` (this file) → `SESSION-00-RECON.md` → `ARCHITECTURE.md` → `ATTACK_SURFACE.md` → `AUDIT_PROGRESS.md`.
2. **Internalize the threat model below.** Only server / Worker / Firestore-rule enforcement counts as a control; client-side checks never do.
3. **Sessions 01–06 are DONE** (`SESSION-01-FIRESTORE.md` … `SESSION-06-DURESS.md`). Read the session reports covering the code you are about to touch, then **begin Session 07 — Client Crypto**, the first `NEXT` row in `AUDIT_PROGRESS.md`.
   - **Files:** `app/src/main/java/com/duoshield/app/crypto/**` (Signal integration, `SignalKeyManager`), the seed-phrase derivation behind `RestoreFromSeedActivity` / `AuthTokenHelper`, group-key handling, and `backup/**`.
   - **Goal:** the threat model says client-side checks are never controls — so score these bugs by their effect on **other** users' confidentiality (key substitution, group-key sharing, backup blob crypto), not on the local user's own plaintext.
   - **Inherited from earlier sessions:** S01's cross-user prekey / one-time-key write gaps and group-key substitution, S03-H1 (`groups/{id}` self-asserted membership — the same collection client crypto trusts), and S06-I3 (duress-PIN deniability depends on `SecurePrefs` being genuinely hardware-backed — verify that here).
   - **Done when:** you create `SESSION-07-CLIENT-CRYPTO.md` (findings as `path:line` + exploit path + severity + fix), flip the Session 07 row in `AUDIT_PROGRESS.md` to DONE, and record severity counts.
4. **Caveat:** `docs/SECURITY_REVIEW_2026-08-04.md` items are marked *"claimed fixed — re-verify,"* not resolved. Do not trust that status for anything in your scope.

---

DuoShield is an end-to-end-encrypted Android messenger with a client-heavy design:
all cryptography happens on-device and the server tier is intended to be a
"zero-knowledge" relay that stores only ciphertext and metadata. That design shifts
the security burden onto **trust-boundary enforcement**: because the client is
assumed fully compromised, every guarantee must be enforced by the server, the
Cloudflare Worker, or the Firestore rules — never by the app.

## Threat model (assume all of the following)

- The Android client is fully compromised and every client-side check is bypassable.
- Attackers can reverse-engineer the APK and extract any compiled-in constant.
- Attackers can intercept, replay, and modify every network request.
- Attackers may already hold one or more authenticated accounts.
- Attackers will automate API abuse.
- The server and cloud services must **never** trust client input.

Everything server-authoritative (Firestore rules, `server/index.js`, `worker/src/index.js`)
is therefore in scope as a control. The on-device crypto is in scope for
confidentiality/integrity of message content, but is explicitly a lower priority than
the trust boundaries, because a compromised client can already read its own plaintext.

## Documents in this folder

| File | Purpose |
|---|---|
| [`README.md`](./README.md) | This overview + how to use the audit set. |
| [`ARCHITECTURE.md`](./ARCHITECTURE.md) | Components, trust boundaries, auth/authz flow, data flow, external services. |
| [`ATTACK_SURFACE.md`](./ATTACK_SURFACE.md) | Every identified attack surface, entry point, and control, with file:line anchors. |
| [`AUDIT_PROGRESS.md`](./AUDIT_PROGRESS.md) | Live status: what is mapped, recommended audit order, session estimate, checklist. |
| [`SESSION-00-RECON.md`](./SESSION-00-RECON.md) | The full reconnaissance report (this phase). Risk ranking + 10-session plan. |

## Current status

**Phase:** Reconnaissance complete. **Vulnerability assessment not yet started.**

- Repository fully mapped (architecture, trust boundaries, attack surface).
- Risk ranking and a 10-session audit plan are defined in `SESSION-00-RECON.md`.
- A prior point-in-time review exists at `docs/SECURITY_REVIEW_2026-08-04.md`; several of
  its Critical/High items appear to have been remediated since (see the git history note
  in `SESSION-00-RECON.md`). **Re-verification of those fixes is part of the planned audit,
  not an assumption.**

## How to continue this audit

1. Read `SESSION-00-RECON.md` end to end.
2. Open `AUDIT_PROGRESS.md`, find the first session marked `NOT STARTED`, and begin there.
3. Record findings in a new `SESSION-NN-<topic>.md` file in this folder (one per session).
4. Update `AUDIT_PROGRESS.md` at the end of every session (status, findings count, next step).
5. Use severity labels: **Critical / High / Medium / Low / Info**, each with a file:line anchor,
   a concrete exploit path aligned to the threat model, and a proposed fix.

## Conventions

- Every finding must cite `path:line` and state which trust boundary it breaks.
- "The client validates X" is **never** a control. Only server/Worker/Firestore-rule
  enforcement counts.
- Prefer proving exploitability against the threat model over theoretical concerns.
