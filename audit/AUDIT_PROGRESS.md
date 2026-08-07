# DuoShield Audit — Progress Tracker

_Last updated: Session 06 (duress & locks) complete — 3H / 3M / 4L / 3I_

## Overall status

| Item | State |
|---|---|
| Repository mapped | ✅ COMPLETE |
| Architecture documented | ✅ COMPLETE (`ARCHITECTURE.md`) |
| Trust boundaries identified | ✅ COMPLETE (TB-1 … TB-10) |
| Attack surface inventoried | ✅ COMPLETE (`ATTACK_SURFACE.md`) |
| Risk ranking | ✅ COMPLETE (`SESSION-00-RECON.md` §Risk Ranking) |
| **Vulnerability assessment** | 🟡 **IN PROGRESS** — S01 (0C/3H/4M/2L/2I) + S02 (0C/1H/1M/4L/3I) [2nd pass] + S03 (0C/3H/3M/4L/3I) + S04 (0C/3H/3M/4L/3I) + S05 (0C/3H/3M/4L/3I) + S06 (0C/3H/3M/4L/3I) done; Session 07 next |

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
| 01 | Firestore rules | `firestore.rules` + `firestore-tests/` | ✅ DONE (P2) | `SESSION-01-FIRESTORE.md` | 0C / 3H / 4M / 2L / 2I |
| 02 | Server auth core | `/mintToken` `/migrateUid` `/createChat`, identities | ✅ DONE (P2) | `SESSION-02-SERVER-AUTH.md` | 0C / 1H / 1M / 4L / 3I |
| 03 | Media pipeline | `/mediaToken` + `worker/src/index.js` (TB-4/8/9) | ✅ DONE | `SESSION-03-MEDIA.md` | 0C / 3H / 3M / 4L / 3I |
| 04 | Server egress & limits | `/linkPreview` SSRF, `/turnCredentials`, rate limits, body/IP | ✅ DONE | `SESSION-04-EGRESS.md` | 0C / 3H / 3M / 4L / 3I |
| 05 | Admin surface | `/admin/*`, `ADMIN_TOKEN`, sessions, audit log | ✅ DONE | `SESSION-05-ADMIN.md` | 0C / 3H / 3M / 4L / 3I |
| 06 | Duress & locks | `/requestLockNonce` `/duress-lock`, `accountLock`, duress wipe | ✅ DONE | `SESSION-06-DURESS.md` | 0C / 3H / 3M / 4L / 3I |
| 07 | Client crypto | `crypto/**`, Signal, seed, group keys, backups | 🔜 **NEXT** | — | — |
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

**Session 03 re-verification result (SEC-A01):** the per-object capability-token *cryptography*
and its fail-closed posture are confirmed correct, and the shared data-plane secret is genuinely
gone from the app's runtime. But the token's **authorization input** is forgeable
(`SESSION-03-MEDIA.md` § S03-H1: any user can create `groups/{id}` with a chat's ID and self-assert
membership), so SEC-A01 is **partially remediated, not resolved** — carry that status into
Session 10.

**Session 04 re-verification result (SSRF fix):** the redirect-revalidation half is confirmed
correct — `fetchFollowingSafeRedirects` re-runs the host predicate on every hop and fails closed.
The predicate itself is not: `isBlockedPreviewHost` never resolves DNS and misses most of IPv6
(`SESSION-04-EGRESS.md` § S04-H1). The prior review's SSRF item is therefore **partially
remediated, not resolved**. `S04-I2` is the outstanding second half of the SEC-A01 cleanup
(dead B2 presign code + live B2 key still in the server env).

**Session 05 re-rating of S04-M1 (action for Session 10):** Session 04 rated S04-M1 (IPv6 /64
defeats every IP-keyed limit, including the admin lockout) as Medium, conditional on
`ADMIN_TOKEN` being high-entropy. Session 05 found that **nothing validates, enforces, or even
documents** its entropy (`SESSION-05-ADMIN.md` § S05-H1, § S05-I1) and there is no global
failure counter and no logging of admin auth failures at all. **S04-M1 is re-rated High** for
the final report.

**Session 06 result (duress feature):** the duress subsystem's internals are well built (uid-bound
single-use nonce, atomic consume-and-lock transaction, correct one-way latch in the rules, good
timing parity on the restore gate), but **neither of its two advertised guarantees currently
holds** (`SESSION-06-DURESS.md`). Three independent defeats: S06-H1 `accountLock` is never checked
by `/mintToken`, so the lock is a client-side advisory check that runs *after* the session is
minted; S06-H2 the wipe leaves plaintext `account_lock_<uid>` WorkManager records that prove a
duress code was entered, breaking plausible deniability; S06-H3 an offline trigger wipes locally
but never locks, and the attacker controls connectivity. S06-H1 is the same *enforcement-location*
bug class as the S01/S03 `groups` gap — group them in the final report.

## How to update this file

At the end of each session: set the row to ✅ DONE, link its `SESSION-NN-*.md`, and record
the count by severity (e.g. `1C / 2H / 3M`). If a Critical is found mid-map, note it at the
top of `SESSION-00-RECON.md` immediately.
