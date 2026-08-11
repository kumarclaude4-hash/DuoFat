# TRUST BOUNDARIES — revalidation map

> **2026-08-11 tracker consolidation note:** `../RISK_REGISTER.md` referenced below is deleted;
> accepted-risk items now live in [`../../BUG_TRACKER.md`](../../BUG_TRACKER.md). This table's
> "modified"/"verified" marks describe **Round 1–2 work only** (server-test-verified) — Round 3 is
> still open per `../ROUND3_REMEDIATION_PLAN.md`, so several boundaries below (notably TB-2/TB-4
> rows that cite Round-3 finding IDs like `S01-M1..M4`) are **more finished on paper here than in
> current source**. Cross-check any specific finding ID against `../../BUG_TRACKER.md` before relying
> on this table's per-boundary verdict.

Maps the ten trust boundaries from `../../audit/SESSION-00-RECON.md` / `ARCHITECTURE.md` to their
remediation state. Each boundary is marked:

- **verified** — reviewed and holds after remediation (source-confirmed).
- **modified** — remediation changed the enforcement here; re-verified after the change.
- **runbook** — code change lands in-repo; an out-of-band console action completes it (tracked in
  `../migration/MIGRATION_PLAN.md`).
- **accepted** — a residual property consciously carried (see `../../BUG_TRACKER.md`).

No boundary is left `blocked`.

| TB | Boundary | Enforcing control | Findings on this boundary | State after remediation |
|---|---|---|---|---|
| TB-1 | Client ↔ Firebase Auth | Custom-token mint | S07-C1, S07-H1, S06-H1, S06-H3, S02-M1, S02-H1, S02-I3 | **modified** — mint now requires a signed challenge over the identity private key, enforces `accountLock` in-transaction, fails closed on missing stored key, and stamps cooldown only post-verification |
| TB-2 | Client ↔ Firestore | `firestore.rules` | S01-H1/H2/H3/M1-M4/L1/L2/I1/I2, S07-H3, S07-M2, S06-M1 | **modified** — prekey writes shrink-only, message content immutable, partnerName protected, field allow-lists, group delete membership-checked, groups server-minted; global oracle field-minimized and **accepted** (ratified) |
| TB-3 | Client ↔ Push/API server | Per-endpoint ID-token verify + rate limits | S04-H1/H2/M1/M3/L1-L3/I1, S02-*, S05-* | **modified** — SSRF resolves+classifies addresses, bounded/timed body read, IPv6-normalized limiters, purge timers, `/status` gated |
| TB-4 | Client ↔ Storage Worker | Per-object HMAC capability token | S03-H1/H2/H3/M1-M3/L1-L4/I1-I3, S08-I3 | **modified** — scope typed + dispatched to one collection (server-minted groups), holder-keyed limits, per-holder byte budget, content-type derived, `nosniff`, orphan-delete race closed |
| TB-5 | Operator ↔ Admin API | `ADMIN_TOKEN` + session cookie + IP lockout | S05-H1/H2/H3/M1-M3/L1-L4/I1-I3, S04-M1 | **modified + runbook** — startup entropy floor, durable audit incl. auth failures, session lifetime/binding, IPv6-normalized lockout, cache-control; token rotation is the runbook step |
| TB-6 | Server ↔ Firestore (Admin SDK) | Trusted; bypasses rules | S08-C1 (key leak), S02-H1, S07-H3 | **modified + runbook** — Admin key no longer shipped in the APK; server writes field-allow-listed; **runbook**: revoke the leaked GCP key |
| TB-7 | Server ↔ Cloudflare TURN | Server-held TURN secrets | S04-M2 | **modified** — aggregate cap + outbound timeout; secrets confirmed server-only |
| TB-8 | Worker ↔ R2/B2 | B2 SigV4 keys (server-side) | S03-I1, S04-I2, S03-L3 | **modified + runbook** — dead presign path + env creds removed from the server; **runbook**: revoke the B2 application key; Worker's bucket-wide creds **accepted** (documented, scope-limited) |
| TB-9 | Server ↔ Worker (shared HMAC) | `MEDIA_TOKEN_SECRET` on both, never in APK | S08-H1, S03-L1 | **modified + runbook** — `WORKER_SECRET` removed from `BuildConfig`; `/stats` moved off the shared bearer; **runbook**: rotate the Worker secret |
| TB-10 | Server ↔ arbitrary URLs | `/linkPreview` SSRF guard | S04-H1, S04-H3/S08-H4 | **modified** — DNS-resolving address-family classifier + pinned connect; link-preview images server-proxied, client refuses off-origin loads |

## Cross-cutting boundary note

Per `../../audit/SESSION-10-SYNTHESIS.md` §"How the themes compose": **S08-C1 (Admin key in the
APK) sat behind TB-1/2/3/5/6/10 simultaneously** — an attacker with the leaked key reached every
boundary *around* rather than *through* its control. Closing S08-C1 + SC-02 (Round 1) is therefore
the precondition for every other boundary's revalidation being meaningful, which is why they are
Round 1. The remaining boundary revalidations in this table are only credible **after** the Round 1
credential-rotation runbook is executed against production (see `../migration/MIGRATION_PLAN.md`).

## Authentication / authorization / secret-ownership / privilege / network paths

- **Authentication path (account):** seed → identity keypair → **signed challenge** → server verifies
  against stored public key → custom token. (Was: public value compared to a stored hash.) — TB-1.
- **Authorization path (data):** ID token → `request.auth.uid` → `firestore.rules` (client) / Admin
  SDK (server, field-allow-listed). — TB-2/TB-6.
- **Authorization path (media):** ID token → `/mediaToken` scope check against a **server-controlled**
  membership source → per-object capability token → Worker verify. — TB-4.
- **Secret ownership:** all backend secrets (GCP SA key, B2 keys, `WORKER_SECRET`, `MEDIA_TOKEN_SECRET`,
  TURN, `ADMIN_TOKEN`) live **server/Worker-side only**; none in the client build. — TB-6/7/8/9,
  enforced by the CI secret-scan gate.
- **Privilege boundary (operator):** `ADMIN_TOKEN` with a startup entropy floor + durable,
  tamper-evident audit of actions and auth failures. — TB-5.
- **Network boundary (egress):** `/linkPreview` resolves + classifies + pins addresses; images
  proxied; no device fetches a sender-chosen host. — TB-10.
