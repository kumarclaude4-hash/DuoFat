# DuoShield — System Architecture (Security View)

> Reconnaissance mapping. Describes the system as a complete production deployment.
> All file:line references are anchors for the later assessment sessions.

## 1. Components & services

| # | Component | Location | Runtime / host | Role |
|---|---|---|---|---|
| C1 | Android client | `app/` | Android device (min SDK 26) | All crypto, UI, local storage. Assumed fully compromised. |
| C2 | Push relay / API server | `server/index.js` (~2,930 LOC) | Node.js on Render.com (`duoshield.onrender.com`) | FCM fan-out **plus** the real authorization brain: token minting, chat creation, media-capability tokens, TURN creds, UID migration, waitlist/invite, duress-lock, admin panel. |
| C3 | Storage Worker | `worker/src/index.js` (~695 LOC) | Cloudflare Workers | Media data-plane: PUT/GET/DELETE encrypted blobs, R2 (hot) → B2 (cold) tiering, quota/rate limits. |
| C4 | Firestore | `firestore.rules`, `firestore.indexes.json` | Firebase (GCP) | Ciphertext + metadata store, presence, signaling, key bundles, waitlist, locks. Client talks to it directly under rules. |
| C5 | Firebase Auth | (managed) | Firebase (GCP) | Identity provider. Custom-token sign-in minted by C2; ID tokens verified by C2. |
| C6 | Firebase Storage | `firebase.json` (rules not in repo) | Firebase (GCP) | Referenced by product docs; **actual media lives in R2/B2 via C3**. Confirm whether FB Storage is used at all (see ATTACK_SURFACE §Storage). |
| C7 | Cloud Functions | `functions/src/index.ts` | Firebase Functions | **Effectively a stub** — only `initializeApp()`. No triggers deployed here today (self-destruct/remote-wipe is client WorkManager + Firestore rules, not a function). Legacy/placeholder. |
| C8 | Backblaze B2 | via C3 (S3-compatible) | Backblaze | Cold-tier encrypted media. Reached only by the Worker with server-side SigV4 keys. |
| C9 | Cloudflare R2 | via C3 | Cloudflare | Hot-tier encrypted media. |
| C10 | Cloudflare TURN | via C2 `/turnCredentials` | Cloudflare Realtime | WebRTC relay for voice/video. Creds minted server-side. |
| C11 | FCM | via C2 | Firebase | Push wake-ups. Data-only payloads (no plaintext body). |

Supporting: `firestore-tests/` (rules unit tests, ~1,068 LOC), `.github/workflows/`
(CI, release APK signing, rules tests), `docs/` (design + prior reviews).

## 2. Trust boundaries

Each boundary is a place where a control **must** exist because the lower-trust side is
attacker-controlled under the threat model.

```
                    ┌────────────────────────────────────────────┐
                    │  TB-1  Client ↔ Firebase Auth                │
   [Android app] ───┤        custom-token sign-in, ID-token issue  ├──► [Firebase Auth]
   (UNTRUSTED)      └────────────────────────────────────────────┘
        │
        │           ┌────────────────────────────────────────────┐
        ├───────────┤  TB-2  Client ↔ Firestore                    ├──► [Firestore]
        │           │        enforced ONLY by firestore.rules      │
        │           └────────────────────────────────────────────┘
        │
        │           ┌────────────────────────────────────────────┐
        ├───────────┤  TB-3  Client ↔ Push/API server (C2)         ├──► [server/index.js]
        │           │        Firebase ID-token verify per endpoint │
        │           └────────────────────────────────────────────┘
        │
        │           ┌────────────────────────────────────────────┐
        ├───────────┤  TB-4  Client ↔ Storage Worker (C3)          ├──► [worker/src/index.js]
        │           │        per-object capability token (SEC-A01) │
        │           └────────────────────────────────────────────┘
        │
        │           ┌────────────────────────────────────────────┐
        └───────────┤  TB-5  Operator ↔ Admin API (C2 /admin/*)    ├──► [server/index.js admin]
                    │        ADMIN_TOKEN + HttpOnly session cookie │
                    └────────────────────────────────────────────┘

  Server-to-service (attacker cannot reach directly, but secrets/SSRF matter):
    TB-6  Server (C2) ↔ Firestore Admin SDK   (bypasses firestore.rules entirely)
    TB-7  Server (C2) ↔ Cloudflare TURN API   (TURN_TOKEN_ID / TURN_API_TOKEN)
    TB-8  Worker (C3) ↔ R2 / B2               (B2 SigV4 keys, MEDIA_TOKEN_SECRET)
    TB-9  Server (C2) ↔ MEDIA_TOKEN_SECRET ↔ Worker (C3)  (shared HMAC signing key)
    TB-10 Server (C2) ↔ arbitrary URLs        (/linkPreview → SSRF surface)
```

Key architectural fact: **the Firestore Admin SDK on C2 bypasses `firestore.rules`.**
So any collection that is `allow read, write: if false` for clients is written only by
the server (waitlist, `_duressNonces`, `_server_health`, `adminAuditLog`, `accountLock`
clears). The rules and the server are two halves of one authorization model and must be
audited together.

## 3. Authentication flow

Identity is **derived from a 24-word seed phrase**, not a phone/email.

1. **Seed → UID.** `SeedPhraseHelper` (BIP-39 + HKDF-SHA256) deterministically derives a
   UID of form `XXXXX-XXXXX-XXX`. Same seed always → same UID (enables restore).
2. **Invite gate (new accounts).** Fresh installs call `POST /requestAccess` → a random
   128-bit `waitlist/{requestId}` doc (`status:pending`). Operator approves via admin panel.
   Client polls `GET /waitlistStatus`.
3. **Token mint.** Client calls `POST /mintToken` with `{userId, identityPubKeyHex, waitlistRequestId}`
   (`server/index.js:1436`). Server runs a Firestore **transaction** that either:
   - New account: requires an `approved` waitlist doc, atomically marks it `used`, and
     writes `identities/{userId} = {uid, identityPubKeyHash}` (first-claim wins), or
   - Existing account: re-verifies `identityPubKeyHash` matches the stored hash (key-continuity / anti-takeover).
   Then mints a Firebase **custom token** for `uid = userId`. Rate-limited per-IP and per-UID.
4. **Sign-in.** Client exchanges the custom token for a Firebase session; subsequent calls
   to C2 send `Authorization: Bearer <Firebase ID token>`, verified with
   `admin.auth().verifyIdToken()` on every protected endpoint.
5. **Restore / UID migration.** After restoring from seed, `POST /migrateUid`
   (`server/index.js:1647`) verifies `auth.uid == userId`, that `identities/{userId}.uid == oldUid`,
   and atomically rewrites chat `participants` / group `members` and copies backups. Guards
   against retargeting another account.

Client-side auth helpers: `app/.../auth/AuthTokenHelper.java`, `auth/WaitlistHelper.java`.

## 4. Authorization flow

Authorization is enforced at **three** independent layers; a finding at any one is real.

### Layer A — Firestore rules (`firestore.rules`, TB-2)
- `users/{uid}` — read by any authed user (needed for key exchange / display / FCM token);
  write by owner only. `public_keys` subcollection allows **cross-user UPDATE scoped to
  `oneTimePreKeys`+`updatedAt` only** (one-time prekey consumption for X3DH) — a deliberately
  narrow hole; verify it cannot touch `identityKey`/`signedPreKey` (`firestore.rules:16-29`).
- `chats/{chatId}` — **create denied to clients** (server-only via `/createChat`). Read/update
  restricted to `participants`; `participants` array is immutable via client update; a
  participant cannot spoof the *other* party's `typing_/online_/lastSeen_/unread_` keys
  (`firestore.rules:43-60`). Messages: create requires `sender==auth.uid && isEncrypted==true`;
  only original sender may set `deletedForAll`; delete limited to sender or expired messages.
- `groups/{groupId}` — members read; only `createdBy` may mutate `members`; `createdBy` immutable;
  `keys/{memberUid}` writable **only by group creator** (anti key-substitution MITM, F27).
- `calls/{callId}` — create gated on an existing bilateral `chats` doc listing both parties;
  candidates + in-call chat restricted to the two participants.
- Owner-only silos: `recovery/{uid}`, `backups/{userId}/**`, `duressEligibility` (read-own, write-denied),
  `accountLock` (**one-way latch**: clients may only ever write `locked==true`; only Admin SDK clears).
- Hard-denied to clients (server/Admin-SDK only): `_server_health`, `rooms` (legacy),
  `conversations` (legacy), `_duressNonces`, `waitlist`, `adminAuditLog`, `backup_logs` (create-only).

### Layer B — Push/API server (`server/index.js`, TB-3)
Every state-changing endpoint verifies the Firebase ID token and checks `decoded.uid` against
the claimed identity, plus a per-UID token-bucket rate limiter (`AUTH_RATE_LIMITS`, `server/index.js:409`).
Endpoints: `/mintToken`, `/requestAccess`, `/waitlistStatus`, `/migrateUid`, `/createChat`,
`/mediaToken`, `/turnCredentials`, `/linkPreview`, `/removeGroupMember`, `/requestLockNonce`,
`/duress-lock`, and the `/admin/*` surface (TB-5).

### Layer C — Storage Worker capability tokens (`worker/src/index.js`, TB-4 / TB-9)
Media data-plane is authorized **per object**, not by a shared secret. Flow (SEC-A01):
1. Client calls `POST /mediaToken {key, op}` on C2. Server validates key format, confirms the
   caller participates in the `chats/{id}` or `groups/{id}` named by the key's middle segment
   (`callerMayAccessScope`, `server/index.js:509`), and returns an HMAC-SHA256 token bound to
   `(op, expiresAt, uidTag, key)` with a 10-min TTL (`signMediaToken`, `server/index.js:495`).
2. Client sends that token as `Authorization: Bearer` to the Worker. `verifyMediaToken`
   (`worker/src/index.js:146`) recomputes the HMAC over the **key from the request path** and
   the HTTP verb, so a token cannot be replayed to a different object or operation. Fails closed
   if `MEDIA_TOKEN_SECRET` is unset.
3. Worker also enforces a strict key allow-list regex (`worker/src/index.js:409`) blocking
   traversal/null-bytes, daily global request cap, and per-isolate rate limit.

> Note: `/stats` on the Worker still uses the legacy shared `WORKER_SECRET` bearer
> (`worker/src/index.js:76-99`), and `WORKER_SECRET` is still compiled into the APK
> (`app/build.gradle:76-77`). The data plane no longer trusts it, but its continued
> presence is a legacy surface to confirm during the Worker session.

## 5. Data flow (message send, media, call)

**1-to-1 text message**
```
Sender app: plaintext ─Signal(Double Ratchet)─► ciphertext
  └─► Firestore write chats/{id}/messages/{msgId} {sender, ciphertext, sigType, isEncrypted:true}
        (allowed by rules: sender==auth.uid && isEncrypted)
Server C2: collectionGroup("messages") onSnapshot  (server/index.js:188)
  └─► looks up chat participants, finds recipient, sends DATA-ONLY FCM wake-up
  └─► markDelivered() transaction (status can only move forward, never read→delivered)
Recipient app: FCM wake → Firestore read → Signal decrypt → local SQLCipher DB
```

**Media (image/voice/video)**
```
Sender: AES-256-GCM encrypt on device
  └─► POST /mediaToken {key:"media/<chatId>/<uuid>.jpg", op:"write"}  → capability token
  └─► PUT <WORKER_URL>/media/<chatId>/<uuid>.jpg  (Bearer capability token) → R2
  └─► Firestore message doc references the object key
Recipient: POST /mediaToken op:"read" → GET from Worker (R2 hot, else B2 cold) → AES-GCM decrypt
Nightly cron (Worker.scheduled): R2 objects older than HOT_TIER_DAYS migrated R2→B2
```

**Voice/video call**
```
Caller: POST /turnCredentials (Bearer ID token) → Cloudflare TURN creds (server-minted)
  └─► create calls/{callId} (rules require pre-existing bilateral chat)
Server C2: calls onSnapshot → FCM "call_invite" to callee (skips calls older than 30s)
Both: exchange SDP/ICE via calls/{callId}/{caller,callee}Candidates (participant-only rules)
Media: WebRTC DTLS-SRTP, relayed via TURN
```

**Account lock / duress**
```
Device (post-wipe): POST /requestLockNonce (auth) → server writes _duressNonces/{nonce}
  └─► POST /duress-lock {nonce} → server transaction consumes nonce, sets accountLock/{uid}.locked=true
Client can only ever set locked=true (rules one-way latch); only operator/Admin SDK can clear.
```

## 6. External services & secrets inventory

| Secret / credential | Held by | Never in APK? | Notes |
|---|---|---|---|
| Firebase service account (`GOOGLE_APPLICATION_CREDENTIALS_JSON`) | C2 | yes | Admin SDK; bypasses rules. |
| `MEDIA_TOKEN_SECRET` | C2 + C3 (identical) | yes (must be) | HMAC signing key for media capability tokens (TB-9). |
| `WORKER_SECRET` | C3 (+ still in APK) | **no — in APK** | Now only guards `/stats`; legacy data-plane use removed. Confirm blast radius. |
| B2 `B2_ACCESS_KEY_ID`/`B2_SECRET_ACCESS_KEY` | C3 | yes | SigV4 to Backblaze. |
| `TURN_TOKEN_ID` / `TURN_API_TOKEN` | C2 | yes | Cloudflare TURN minting. |
| `ADMIN_TOKEN` | C2 | yes | Operator admin panel (TB-5). |
| Release keystore (`KEYSTORE_*`) | GitHub Actions secrets | yes | APK signing. |
| B2 bucket/region/endpoint | APK (`buildConfigField`) | public config, not secret | — |

## 7. Notable design invariants (from docs/ARCHITECTURE.md — to be verified, not trusted)

- SQLCipher DB key is random-per-install in `EncryptedSharedPreferences`, **not** seed-derived.
- `FirebaseCostGuard` singleton gates all Firestore reads/writes (cost control, not security).
- Status-downgrade guard on message `status` writes (rules + server transaction).
- Signal identity handling is TOFU (trust-on-first-use) with a safety-number banner.
- `MessageBuilder` always includes an `id` field; listener skips `id==null` docs.
