# DuoShield Audit — Progress Tracker

_Last updated: Session 01 (Firestore authorization)_

## Overall status

| Item | State |
|---|---|
| Repository mapped | ✅ COMPLETE |
| Architecture documented | ✅ COMPLETE (`ARCHITECTURE.md`) |
| Trust boundaries identified | ✅ COMPLETE (TB-1 … TB-10) |
| Attack surface inventoried | ✅ COMPLETE (`ATTACK_SURFACE.md`) |
| Risk ranking | ✅ COMPLETE (`SESSION-00-RECON.md` §Risk Ranking) |
| **Vulnerability assessment** | 🟡 **IN PROGRESS** — Session 01 done (0C / 2H / 3M / 2L / 2I); Session 02 next |

**Estimated effort:** ~10 focused sessions (see plan below). Sessions 1–5 cover the
server-authoritative trust boundaries (highest value under the threat model) and should
come before any client-only deep dive.

## Recommended audit order (why this sequence)

The order maximizes security coverage by following the trust model: review the surfaces
that a fully-compromised client attacks **first**, because those are the only real
controls. Client-only crypto comes later because a compromised client can already read
its own plaintext — its bugs matter mainly for *other* users' confidentiality, which is
ultimately mediated by the same server/rules boundaries reviewed earlier.

1. **Firestore rules** — the single largest authorization surface reachable directly by a
   compromised client; everything else assumes it holds.
2. **Push/API server core auth** (`/mintToken`, `/migrateUid`, `/createChat`, identity) —
   the brain that mints trust; a flaw here undermines every rule above.
3. **Media capability tokens + Worker** (TB-4/TB-9) — cross-service HMAC trust; historically
   the weakest link (SEC-A01 rewrite).
4. **SSRF / outbound + TURN + rate limits** — server-as-a-confused-deputy surfaces.
5. **Admin surface** — high blast radius, smaller code.
6. **Duress / account-lock / waitlist** — abuse-resistance + one-way latch correctness.
7. **Client crypto & key management** — Signal integration, seed, group keys, backups.
8. **Client platform hardening** — manifest, deep links, SQLCipher, secure prefs, secrets-in-APK.
9. **Supply chain & CI/CD** — dependencies, release signing, config.
10. **Synthesis & regression** — re-verify prior review fixes, write final report.

## Session ledger

| # | Session | Scope | Status | Report | Findings |
|---|---|---|---|---|---|
| 00 | Reconnaissance | Whole repo map | ✅ DONE | `SESSION-00-RECON.md` | mapping only |
| 01 | Firestore rules | `firestore.rules` + `firestore-tests/` | ✅ DONE | `SESSION-01-FIRESTORE.md` | 0C / 2H / 3M / 2L / 2I |
| 02 | Server auth core | `/mintToken` `/migrateUid` `/createChat`, identities | ⛔ NOT STARTED | — | — |
| 03 | Media pipeline | `/mediaToken` + `worker/src/index.js` (TB-4/9) | ⛔ NOT STARTED | — | — |
| 04 | Server egress & limits | `/linkPreview` SSRF, `/turnCredentials`, rate limits, body/IP | ⛔ NOT STARTED | — | — |
| 05 | Admin surface | `/admin/*` | ⛔ NOT STARTED | — | — |
| 06 | Duress & locks | `/requestLockNonce` `/duress-lock`, `accountLock`, waitlist | ⛔ NOT STARTED | — | — |
| 07 | Client crypto | `crypto/**`, Signal, seed, group keys, backups | ⛔ NOT STARTED | — | — |
| 08 | Client platform | manifest, deep links, SQLCipher, SecurePrefs, APK secrets | ⛔ NOT STARTED | — | — |
| 09 | Supply chain / CI | deps, lockfiles, `.github/workflows/**` | ⛔ NOT STARTED | — | — |
| 10 | Synthesis | regression vs `docs/SECURITY_REVIEW_2026-08-04.md`, final report | ⛔ NOT STARTED | — | — |

## Prior-work note (must be re-verified, not assumed)

`docs/SECURITY_REVIEW_2026-08-04.md` listed 1 Critical + 4 High + Mediums. The git history
since then shows commits titled `refactor: switch from shared WORKER_SECRET to per-object
capability tokens` (SEC-A01) and `feat: redact sensitive user IDs in logs`, and the current
code contains the fail-closed Worker auth, per-object capability tokens, redirect-revalidating
SSRF guard, transactional duress-lock, and bounded body readers. **Treat these as "claimed
fixed" and independently re-verify each in the relevant session** — do not carry them forward
as resolved.

## How to update this file

At the end of each session: set the row to ✅ DONE, link its `SESSION-NN-*.md`, and record
the count by severity (e.g. `1C / 2H / 3M`). If a Critical is found mid-map, note it at the
top of `SESSION-00-RECON.md` immediately.
