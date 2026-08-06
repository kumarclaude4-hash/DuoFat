# DuoShield Security Audit

This folder is the **single source of truth** for the DuoShield security assessment.
It is written so that any security engineer can pick up the audit **cold** — with no
prior conversation context — and continue exactly where the previous session stopped.

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
