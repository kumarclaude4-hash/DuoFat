# DuoShield — Attack Surface Inventory

> Every externally-reachable or trust-boundary-crossing surface, with the control that
> is supposed to defend it and the file:line to review. This is a **map**, not a findings
> list — exploitability is assessed in the per-session reports.

Legend for "Trust in": what the surface receives from an attacker-controlled party.

---

## A. Authentication & identity

| ID | Surface | Entry point | Control today | Review anchor |
|---|---|---|---|---|
| A1 | New-account invite | `POST /requestAccess` | Per-IP rate limit (5/15min); random 128-bit requestId | `server/index.js:1558` |
| A2 | Invite status probe | `GET /waitlistStatus` | Separate poll bucket (60/15min); requestId format check | `server/index.js:1594` |
| A3 | Custom-token mint | `POST /mintToken` | Per-IP + per-UID cooldown; Firestore txn: waitlist consume + identity-hash continuity | `server/index.js:1436` |
| A4 | UID migration / restore | `POST /migrateUid` | ID-token verify; `uid==userId`; `identities.uid==oldUid`; atomic swaps | `server/index.js:1647` |
| A5 | Identity binding record | `identities/{userId}` rules | read=any authed (enumeration oracle); create/update pin `identityPubKeyHash` | `firestore.rules:252-264` |
| A6 | Seed→UID derivation | on-device | BIP-39 + HKDF-SHA256; client-side only | `app/.../crypto/SeedPhraseHelper.java` |
| A7 | Client token cache | on-device | Firebase session mgmt | `app/.../auth/AuthTokenHelper.java` |

## B. Authorization (Firestore rules, TB-2)

| ID | Surface | Control | Review anchor |
|---|---|---|---|
| B1 | User doc read (any authed) | Intentional openness for key exchange/FCM/display — enumeration/metadata surface | `firestore.rules:7-9` |
| B2 | Cross-user prekey consume | UPDATE scoped to `oneTimePreKeys`+`updatedAt` only (must not touch identity/signed keys) | `firestore.rules:16-29` |
| B3 | Chat create (client-denied) | `allow create: if false` — server-only | `firestore.rules:44` |
| B4 | Chat update presence spoofing | participants immutable; partner presence keys blocked | `firestore.rules:52-60` |
| B5 | Message forge/plaintext-inject | create requires `sender==auth.uid && isEncrypted==true` | `firestore.rules:68-72,130-134` |
| B6 | Message tamper/delete | sender immutable; `deletedForAll` sender-only; delete sender-or-expired | `firestore.rules:77-92` |
| B7 | Group membership tamper | only `createdBy` edits `members`; `createdBy` immutable | `firestore.rules:109-120` |
| B8 | Group key substitution (MITM) | `keys/{memberUid}` writable only by group creator | `firestore.rules:149-155` |
| B9 | Call injection/eavesdrop | create gated on bilateral chat; candidates/chat participant-only | `firestore.rules:167-211` |
| B10 | Owner silos | `recovery`, `backups/**`, `duressEligibility` owner-scoped | `firestore.rules:266-324` |
| B11 | Account-lock latch | client may only write `locked==true`; no clear/delete | `firestore.rules:341-354` |
| B12 | Legacy collections | `rooms`, `conversations` hard-denied | `firestore.rules:231-247` |
| B13 | Server-only collections | `_server_health`, `_duressNonces`, `waitlist`, `adminAuditLog` denied to clients | `firestore.rules:216,360-388` |

## C. Push / API server endpoints (`server/index.js`, TB-3)

| ID | Endpoint | Auth | Sensitive action | Anchor |
|---|---|---|---|---|
| C1 | `POST /createChat` | ID token, `uid==myUid`, both in `identities` | Admin-SDK write to `chats` (bypasses rules) | `:1839` |
| C2 | `POST /mediaToken` | ID token + `callerMayAccessScope` | Mints capability token for Worker (TB-9) | `:1942` |
| C3 | `POST /turnCredentials` | ID token | Calls Cloudflare with server TURN secret | `:2032` |
| C4 | `POST /linkPreview` | ID token + rate limit | **SSRF-prone**: server-side fetch of user URL w/ redirect re-validation | `:2215`, guard `:709-743` |
| C5 | `POST /removeGroupMember` | ID token | Group membership + key rotation | `:2309` |
| C6 | `POST /requestLockNonce` | ID token (limit 3/min) | Writes `_duressNonces` (Admin SDK) | `:2362` |
| C7 | `POST /duress-lock` | nonce (64-hex) | Consumes nonce txn, sets `accountLock` | `:2422` |
| C8 | `GET /health` `/status` `/` | none | Info only | `:2105,2112,2128` |
| C9 | Body handling | `MAX_BODY_BYTES` 64KB via `collectBody`/`readBody` | DoS guard; verify every route uses it | `:745-797` |
| C10 | Client-IP derivation | `getClientIp` uses rightmost XFF (proxy-appended) | Rate-limit integrity vs. XFF spoofing | `:389-393` |
| C11 | FCM fan-out | server-trusted | Data-only payloads; `notificationBody` fixed strings | `:92,122-161` |
| C12 | Error responses | mixed | Verify no `e.message` leakage to clients | throughout |

## D. Admin surface (`server/index.js` `/admin/*`, TB-5)

| ID | Endpoint | Control | Anchor |
|---|---|---|---|
| D1 | `POST /admin/login` | `ADMIN_TOKEN` constant-time compare; per-IP lockout; HttpOnly session cookie | `:2503` |
| D2 | `GET /admin` | Static HTML shell; per-response CSP nonce; `textContent`-only render | `:2551`, HTML `:805` |
| D3 | `POST /admin/logout` | Server-side session revoke | `:2539` |
| D4 | `/admin/api/waitlist` (+`/approve`) | `requireAdminAuth`; grants account access | `:2575,2603` |
| D5 | `/admin/api/locked` (+`/unfreeze`) | `requireAdminAuth`; clears `accountLock` (Admin SDK) | `:2655,2681` |
| D6 | `/admin/api/duress/*` | `requireAdminAuth`; enroll/revoke/lookup duress accounts | `:2728-2884` |
| D7 | `/admin/api/auditlog` | `requireAdminAuth`; reads `adminAuditLog` | `:2885` |

## E. Storage Worker (`worker/src/index.js`, TB-4/TB-8/TB-9)

| ID | Surface | Control | Anchor |
|---|---|---|---|
| E1 | `PUT /<key>` upload | capability token (write); key regex; size pre+post check; R2 cap | `:439-483`, verify `:146,409` |
| E2 | `GET /<key>` download | capability token (read); R2→B2 fallback | `:486-527` |
| E3 | `DELETE /<key>` | capability token (delete); dual-tier delete race guard | `:530-570` |
| E4 | `GET /stats` | **legacy shared `WORKER_SECRET`** bearer | `:362-398`, auth `:76-99` |
| E5 | `GET /health` `/` | unauthenticated | `:353` |
| E6 | Key format allow-list | regex blocks traversal/null/arbitrary prefix | `:409` |
| E7 | Capability verify | HMAC over path key + verb + expiry; fails closed | `:146-194` |
| E8 | Quota / rate limits | daily global cap (sampled); per-isolate per-credential-hash bucket | `:224-287` |
| E9 | Nightly tiering cron | R2→B2 migration; ETag race guards; concurrency non-atomic | `:587-693` |
| E10 | CORS | wildcard `*` (intentional for native client) | `:54-64` |

## F. On-device / client platform (C1)

| ID | Surface | Control | Anchor |
|---|---|---|---|
| F1 | Exported components | Manifest — verify only intended activities exported | `app/src/main/AndroidManifest.xml` (exported=true at :59, :133) |
| F2 | Deep links `duoshield://add/...` | client-side add-contact w/ user confirm; validate path | `app/.../ui/AddContactActivity.java` |
| F3 | SQLCipher key mgmt | random key in `EncryptedSharedPreferences`; race/flush concerns | `app/.../db/DatabaseKeyProvider.java` |
| F4 | Secure prefs fallback | `EncryptedSharedPreferences`; verify no silent plaintext fallback | `app/.../util/SecurePrefs.java` |
| F5 | App lock / duress PIN | PBKDF2-HMAC-SHA256 310k; ref-count lock lifecycle | `app/.../util/AppLockManager.java`, `security/DuressManager.java` |
| F6 | Compiled secrets in APK | `WORKER_SECRET`, B2 config as `buildConfigField` — extractable | `app/build.gradle:50-77` |
| F7 | Direct Firestore REST writes | `FirestoreRestWriter` — confirm it respects same auth path | `app/.../util/FirestoreRestWriter.java` |
| F8 | Media encryption on device | AES-256-GCM, 12-byte SecureRandom IV | `app/.../util/B2StorageHelper.java`, `crypto/*` |
| F9 | Signal protocol integration | libsignal 0.54.1; TOFU identity handling | `app/.../crypto/signal/*` |
| F10 | Backup crypto | PBKDF2 + AES-GCM, seed-based | `app/.../crypto/BackupCryptoHelper.java`, `backup/*` |
| F11 | Notification content render | displays server-supplied body verbatim (no allowlist) | `app/.../notifications/DuoShieldMessagingService.java` |

## G. Cross-service secrets & config (TB-6..TB-10)

| ID | Surface | Concern | Anchor |
|---|---|---|---|
| G1 | `MEDIA_TOKEN_SECRET` sync | Must be identical + secret on C2 and C3; rotation story | `server/index.js:465`, `worker/src/index.js:146` |
| G2 | Firebase Admin credentials | Full DB bypass if leaked | `server/index.js:6-20` |
| G3 | TURN secrets | server-only; never to client | `server/index.js:2061-2078` |
| G4 | SSRF egress from C2 | `/linkPreview` + `/turnCredentials` outbound fetch | `server/index.js:709-743,2070` |
| G5 | CI/CD release signing | GitHub Actions secrets; supply-chain | `.github/workflows/release.yml` |
| G6 | Dependency supply chain | server/worker/functions/firestore-tests `package.json` + lockfiles | each workspace |

## H. Legacy / dead-code surfaces (confirm truly inert)

| ID | Item | Note | Anchor |
|---|---|---|---|
| H1 | `functions/src/index.ts` | Only `initializeApp()` — stub; confirm nothing deployed relies on it | `functions/src/index.ts` |
| H2 | `rooms` / `conversations` rules | Hard-denied but present; confirm no client references remain | `firestore.rules:231-247` |
| H3 | `WORKER_SECRET` in APK | Data-plane no longer uses it; still shipped + guards `/stats` | `app/build.gradle:76`, `worker:76-99` |
| H4 | `VoiceNoteHelper` | Documented as throwing `UnsupportedOperationException` (dead-code guard) | per docs/ARCHITECTURE |
