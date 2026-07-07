---
name: DuoShield custom token auth
description: Firebase auth uses custom tokens (uid=userId) instead of signInAnonymously; eliminates UID-mismatch after sign-out; server /mintToken endpoint; one-time Firestore migration on restore
---

## The Problem
`signInAnonymously()` creates a new random Firebase UID on every sign-out → sign-in.
All Firestore data (chat `participants`, `users/{uid}`, identity records) still
references the OLD uid → data appears gone.

## The Fix
Push server mints Firebase Custom Tokens via `admin.auth().createCustomToken(userId)`
where `userId` is derived deterministically from the BIP39 seed phrase.
UID = userId = permanent — never changes across sign-outs.

**Why:** Custom-token uid is caller-controlled; seed is 128-bit entropy so the userId
is unguessable; server verifies `sha256(identityPubKeyHex) == storedIdentityPubKeyHash`
before minting for existing accounts.

## Key Components
- `server/index.js` — `POST /mintToken` endpoint; rate-limited (1/userId/60s);
  verifies pubKeyHash for existing accounts; new accounts minted unconditionally.
- `AuthTokenHelper.java` (`com.duoshield.app.auth`) — HTTP POST to `/mintToken`,
  then `signInWithCustomToken()`; callback on main thread.
- `DisplayNameActivity` — now generates mnemonic FIRST, derives userId + identityKeyPair,
  calls AuthTokenHelper, then navigates to SeedPhraseDisplayActivity.
  No more signInAnonymously().
- `RestoreFromSeedActivity` — Step C replaced with AuthTokenHelper. If identity doc
  contains an oldUid ≠ currentUid (old anonymous uid), runs `migrateOldUid()` which:
  1. Copies `users/{oldUid}` → `users/{newUid}`, deletes old.
  2. Rewrites `chats` participants: arrayRemove(oldUid) + arrayUnion(newUid).
  3. Rewrites `groups` members: same pattern.
  Migration is idempotent.

## How to apply
- `PUSH_SERVER_URL` must be set in `local.properties` (`push.server.url=...`)
  or env var `PUSH_SERVER_URL` for `BuildConfig.PUSH_SERVER_URL` to have a value.
- Server must have `firebase-admin` auth permission to call `createCustomToken()`.
  (Same service account used for FCM already has this by default.)
- Existing accounts: migration runs automatically on first restore with new code.
