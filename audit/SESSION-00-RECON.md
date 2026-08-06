# SESSION 00 — Reconnaissance Report

**Objective:** Understand the repository, map the architecture and trust boundaries, and
produce a structured audit plan a fresh engineer can execute cold. **Not** an exhaustive
vulnerability review.

**Result:** Repository fully mapped. No obvious *unremediated* Critical was discovered
during mapping (the previously-documented Critical/High items appear addressed in the
current tree; re-verification is scheduled — see §9). Ready to begin Session 01.

---

## 1. Repository architecture (summary)

DuoShield is a seed-phrase-based, end-to-end-encrypted Android messenger built as a
**client-heavy** system with a small but security-critical server tier. Full detail in
[`ARCHITECTURE.md`](./ARCHITECTURE.md); the essentials:

- **Android client** (`app/`, ~170 Java classes) — all cryptography (Signal Protocol /
  libsignal 0.54.1, AES-256-GCM media/group/backup, SQLCipher local DB). Assumed fully
  compromised.
- **Push/API server** (`server/index.js`, ~2,930 LOC, Node.js on Render) — despite the
  "stateless FCM relay" framing, this is the **authorization brain**: token minting,
  chat creation, per-object media tokens, TURN credentials, UID migration, invite
  waitlist, duress-lock, and the operator admin panel. Uses the Firebase Admin SDK, which
  **bypasses Firestore rules**.
- **Storage Worker** (`worker/src/index.js`, ~695 LOC, Cloudflare) — encrypted media
  data-plane (PUT/GET/DELETE), R2 hot → B2 cold tiering, quotas, per-object capability
  tokens.
- **Firestore** (`firestore.rules`, ~392 lines) — ciphertext + metadata + signaling +
  key bundles; the primary surface a compromised client attacks directly.
- **Firebase Auth** — custom-token sign-in minted by the server; ID tokens verified per
  endpoint.
- **Cloud Functions** (`functions/src/index.ts`) — effectively a **stub** (`initializeApp()`
  only). No live triggers here today.
- **External:** Backblaze B2, Cloudflare R2, Cloudflare TURN, FCM.

## 2. Trust boundaries (TB-1 … TB-10)

| TB | Boundary | Enforcing control |
|---|---|---|
| TB-1 | Client ↔ Firebase Auth | Custom-token mint (server) + ID-token issuance |
| TB-2 | Client ↔ Firestore | `firestore.rules` (only control) |
| TB-3 | Client ↔ Push/API server | Per-endpoint Firebase ID-token verify + per-UID rate limits |
| TB-4 | Client ↔ Storage Worker | Per-object HMAC capability token (SEC-A01) |
| TB-5 | Operator ↔ Admin API | `ADMIN_TOKEN` + HttpOnly session cookie + IP lockout |
| TB-6 | Server ↔ Firestore (Admin SDK) | Trusted; **bypasses rules** — audit alongside rules |
| TB-7 | Server ↔ Cloudflare TURN | Server-held TURN secrets |
| TB-8 | Worker ↔ R2/B2 | B2 SigV4 keys (server-side) |
| TB-9 | Server ↔ Worker (shared HMAC) | `MEDIA_TOKEN_SECRET` identical on both, never in APK |
| TB-10 | Server ↔ arbitrary URLs | `/linkPreview` SSRF guard (blocklist + redirect re-check) |

See [`ARCHITECTURE.md` §2](./ARCHITECTURE.md) for the diagram and the critical fact that
Admin-SDK writes bypass the client rules, making the rules + server one combined model.

## 3. Data flow

Documented in [`ARCHITECTURE.md` §5](./ARCHITECTURE.md): text message (Signal → Firestore →
server FCM fan-out → recipient decrypt), media (device AES-GCM → capability token → Worker
R2/B2 → decrypt), calls (server-minted TURN → bilateral-chat-gated `calls` doc → WebRTC
DTLS-SRTP), and the duress/account-lock nonce flow. Plaintext never leaves the device;
the server sees only ciphertext + routing metadata; FCM payloads are fixed generic strings.

## 4. Attack surfaces

Full inventory with file:line anchors in [`ATTACK_SURFACE.md`](./ATTACK_SURFACE.md),
grouped as: **A** Authentication/identity · **B** Firestore authorization ·
**C** Server endpoints · **D** Admin · **E** Storage Worker · **F** Client/platform ·
**G** Cross-service secrets & SSRF egress · **H** Legacy/dead code.

The highest-signal entry points for a compromised, authenticated attacker:
- Direct Firestore reads/writes under the rules (B1–B13).
- `/mintToken` + `/migrateUid` identity logic (A3–A4) — the root of trust.
- `/mediaToken` scope check + Worker capability verification (C2, E7) — cross-user media access.
- `/linkPreview` outbound fetch (C4/G4) — SSRF into internal/metadata endpoints.
- Admin panel (D1–D7) — full operator blast radius.

## 5. Security-critical components (rank of "if this breaks, how bad")

1. `firestore.rules` — the only thing between an authenticated attacker and every other
   user's ciphertext/metadata/signaling.
2. `server/index.js` identity + `/createChat` + `/mediaToken` — mints and scopes trust;
   Admin-SDK writes ignore the rules entirely.
3. `worker/src/index.js` `verifyMediaToken` + key allow-list — guards all media objects.
4. `MEDIA_TOKEN_SECRET` handling (TB-9) — a leak or mismatch collapses TB-4.
5. Client crypto (`crypto/**`) — Signal session/identity, group-key distribution, backup/seed.
6. Duress/account-lock (`accountLock` latch, `_duressNonces`) — safety feature; correctness-critical.
7. Admin auth (`ADMIN_TOKEN`, session cookie).

## 6. Risk ranking (by subsystem)

### CRITICAL
- **Firestore rules (TB-2).** Directly attacker-reachable by any of the (assumed) held
  accounts; a single over-permissive rule exposes other users' data or enables MITM (group
  key substitution, prekey overwrite). Largest blast radius, most nuanced logic
  (cross-user prekey update, presence-key diffing, deletedForAll gating).
- **Server identity/token minting (`/mintToken`, `/migrateUid`, `identities`).** Root of
  trust for the whole system; a takeover or migration-retarget flaw compromises accounts
  irrespective of client crypto.
- **Media capability tokens (TB-9) + Worker data-plane.** Historically the weakest link
  (pre-SEC-A01 shared secret in the APK). Cross-service HMAC correctness governs access to
  all media; must confirm the rewrite fully closed the old cross-user read/overwrite/delete.

### HIGH
- **`/linkPreview` SSRF (TB-10).** Server-side fetch of attacker-supplied URLs; internal/metadata
  reachability if the per-hop redirect re-validation has any gap.
- **Admin surface (TB-5).** Grants/revokes account access and clears locks via Admin SDK;
  auth bypass or session flaw = operator-level control.
- **Worker tiering / quota concurrency.** Non-atomic R2/B2 operations and soft caps —
  data-integrity (orphaned/stale ciphertext) and cost-DoS exposure.
- **Rate-limit / IP-trust integrity (`getClientIp`, per-UID buckets).** In-memory limiters
  reset on Render cold start; XFF handling must not be spoofable.

### MEDIUM
- **Duress/account-lock correctness.** One-way latch and single-use nonce transactions;
  abuse-resistance rather than direct data exposure.
- **Client key/DB management (SQLCipher key, SecurePrefs fallback).** Availability/at-rest
  confidentiality on the device; matters when the device itself is the adversary's target.
- **Identity enumeration oracle (`identities`/`users` readable by any authed user).** Metadata
  exposure; possibly an accepted product trade-off — needs explicit decision.
- **Info leakage** — error `e.message` bodies, UID logging (partly redacted already).

### LOW / INFO
- Legacy/dead surfaces (`functions` stub, `rooms`/`conversations` denied rules,
  `WORKER_SECRET` still in APK guarding only `/stats`, `VoiceNoteHelper`).
- Deep-link input validation (`duoshield://add/...`).
- Wildcard CORS on the Worker (intentional for native client).
- Dev-only dependency advisories (`firestore-tests` transitive `brace-expansion`).
- Notification body rendered verbatim client-side (defense-in-depth, not an active leak).

## 7. Recommended audit order (and rationale)

Follow the trust model — controls first, client-only last. Detailed per-session scope in
[`AUDIT_PROGRESS.md`](./AUDIT_PROGRESS.md).

1. **`firestore.rules` + `firestore-tests/`** — everything else assumes the rules hold, and
   they are the surface a compromised client hits directly. Review the tests too: they encode
   the intended contract and reveal gaps by omission.
2. **Server auth core** (`/mintToken`, `/migrateUid`, `/createChat`, `identities`) — the
   trust root; must be sound before trusting anything it emits (custom tokens, chat docs).
3. **Media pipeline** (`/mediaToken` + Worker) — depends on #2's identity; the HMAC bridge
   (TB-9) is only as good as the scope check upstream.
4. **Server egress & limits** (`/linkPreview`, `/turnCredentials`, rate/body/IP) — server-as-
   confused-deputy; independent of the data model, so reviewable once endpoints are understood.
5. **Admin** — small, high-impact; review after the user-facing server so shared helpers
   (auth, sessions, body reader) are already understood.
6. **Duress & locks** — builds on server + rules already reviewed (`accountLock` latch spans both).
7. **Client crypto** — now that the boundaries are mapped, assess what the client is trusted to
   do correctly for *other* users (Signal identity/TOFU, group-key distribution, backup/seed).
8. **Client platform** — manifest/exported components, deep links, SQLCipher, SecurePrefs,
   compiled secrets; local-attacker and lost-device scenarios.
9. **Supply chain / CI/CD** — dependencies and release-signing integrity underpin all of the above.
10. **Synthesis** — regression-check the prior review's fixes and write the consolidated report.

## 8. Recommended 10-session audit plan

| # | Session | Primary files | Goal |
|---|---|---|---|
| 01 | Firestore authorization | `firestore.rules`, `firestore-tests/rules.test.js` | Prove each collection's create/read/update/delete cannot be abused cross-user; find rules the tests don't cover. |
| 02 | Server auth & identity | `server/index.js` (`:1436` `/mintToken`, `:1647` `/migrateUid`, `:1839` `/createChat`), `identities` rules | Verify token mint, invite consumption, key-continuity, and migration cannot take over or retarget accounts. |
| 03 | Media capability + Worker | `server/index.js` (`:495`,`:509`,`:1942`), `worker/src/index.js` (`:146`,`:409`,`:439-570`) | Confirm per-object scope check + HMAC binding fully close cross-user media read/write/delete; key-format bypass; fail-closed. |
| 04 | Server egress & abuse controls | `server/index.js` (`:709-743` SSRF, `:2032` TURN, `:389-457` limits, `:745-797` body, `:389-393` IP) | SSRF into internal/metadata; TURN secret exposure; rate-limit spoof/reset; body/IP handling. |
| 05 | Admin panel | `server/index.js` `/admin*` (`:2496-2929`) | Auth bypass, session fixation, CSRF, lockout evasion, audit-log integrity, XSS in shell. |
| 06 | Duress, locks, waitlist | `server/index.js` (`:2362`,`:2422`, waitlist), `accountLock`/`_duressNonces` rules | One-way latch integrity, single-use nonce races, invite-bypass to create accounts. |
| 07 | Client cryptography | `app/.../crypto/**`, `crypto/signal/**`, `BackupCryptoHelper`, `SeedPhraseHelper`, `GroupCipherHelper` | IV/nonce reuse, TOFU identity handling, group-key trust, seed entropy, backup KDF. |
| 08 | Client platform hardening | `AndroidManifest.xml`, `ui/AddContactActivity`, `db/DatabaseKeyProvider`, `util/SecurePrefs`, `util/FirestoreRestWriter`, `app/build.gradle` | Exported components, deep-link input, at-rest key handling, secure-prefs fallback, compiled secrets. |
| 09 | Supply chain & CI/CD | `*/package.json` + lockfiles, `functions/`, `.github/workflows/**` | Vulnerable/abandoned deps, release-signing secret handling, workflow injection, stub-function confirmation. |
| 10 | Synthesis & regression | all prior sessions + `docs/SECURITY_REVIEW_2026-08-04.md` | Re-verify each prior Critical/High fix; deduplicate; produce final prioritized report. |

Each session ends with a `SESSION-NN-<topic>.md` (findings with `path:line`, exploit path
tied to the threat model, severity, fix) and an `AUDIT_PROGRESS.md` update.

## 9. Prior review & remediation status (to re-verify, not assume)

`docs/SECURITY_REVIEW_2026-08-04.md` recorded: **1 Critical** (Worker fails open with no
`WORKER_SECRET`), **4 High** (no per-object Worker authz / shared secret in APK; legacy
`rooms`/`conversations` rules; Worker tiering races; `/linkPreview` redirect SSRF), plus
Mediums (mintToken cooldown race, unbounded body readers, duress-lock nonce race, IP
logging/XFF trust, error-detail leakage, DB-key race, SecurePrefs fallback).

The current tree shows corresponding changes — fail-closed Worker auth (`worker:76-99`),
per-object capability tokens (SEC-A01, both C2 and C3), denied legacy collections
(`firestore.rules:231-247`), redirect-revalidating SSRF guard (`server:709-743`),
transactional `/duress-lock` (`server:2446-2466`), bounded `collectBody`/`readBody`
(`server:745-797`), transactional mintToken identity claim (`server:1483-1519`), and log
redaction (`uidTag`/`ipTag`). Git log confirms commits for the WORKER_SECRET→capability
refactor and log redaction.

**These are treated as "claimed remediated" only.** Independent re-verification against the
live source is Session 10 (and inline within each relevant session). Do not mark any prior
finding resolved without a fresh check.

## 10. Recon-phase observations worth an early look (not yet assessed)

Flagged during mapping so they are not lost; none confirmed exploitable yet:
- `WORKER_SECRET` is still compiled into the APK (`app/build.gradle:76-77`) and still guards
  `/stats`. Confirm its blast radius is now limited to stats only (Session 03/08).
- `users/{uid}` and `identities/{userId}` are readable by **any** authenticated user
  (`firestore.rules:8,253`) — a UID/enumeration + metadata oracle. Confirm this is an accepted
  product trade-off (Session 01).
- In-memory rate limiters and mint cooldowns (`server/index.js`) reset on Render cold starts /
  do not span instances — evaluate real-world effectiveness (Session 04).
- `functions/src/index.ts` is a stub; ensure no deployed trigger is silently expected here,
  since the README implies a self-destruct Cloud Function (Session 09).
- `FirestoreRestWriter` (client) — confirm it flows through the same authenticated path as the
  SDK and cannot be used to sidestep any client-side gating (Session 08).

---

_End of Session 00. Next: Session 01 — Firestore authorization._
