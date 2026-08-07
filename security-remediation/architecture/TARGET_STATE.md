# TARGET STATE — intended architecture after remediation

The final architecture this program drives toward. It changes the *placement* of controls (the
audit's dominant root cause, Theme A/B: "mechanisms built carefully and placed incorrectly"), not
the cryptographic core (which the audit confirmed correct — `../../audit/SESSION-10-SYNTHESIS.md`
§10).

## 1. Account authentication (was TB-1's Critical)

**Before:** client sends `{userId, identityPubKeyHex}`; server compares `sha256(pubkey)` to a stored
hash. Both inputs are public → anyone with a target's Account ID takes over (`S07-C1`).

**Target:** proof-of-possession of the identity **private** key.
1. `POST /authChallenge {userId}` → server stores a single-use 32-byte nonce, short TTL.
2. Client signs `"DUOSHIELD_AUTH_V1" || userId || nonce || ts` with the identity private key.
3. `POST /mintToken {userId, identityPubKeyHex, nonce, signature}` → server verifies the signature
   against the **stored full public key** (not a hash), consumes the nonce in the mint transaction,
   checks `accountLock.locked !== true` in the same transaction, then mints. Missing stored key ⇒
   deny (fail closed). Cooldown stamped only after a verified mint.

The identity public key (not just its hash) is stored in `identities/{userId}` so verification is
possible. This closes `S07-C1`, `S07-H1`/`S02-L1`, `S06-H1` (lock enforced where the credential is
issued), and `S02-M1` in one path.

## 2. Media authorization (was TB-4's SEC-A01 residue)

**Before:** object key `media/<scopeId>/<uuid>`; server tries chat *and* group membership; groups are
client-created with a client-chosen ID → forge `groups/{chatId}` and self-assert membership (`S03-H1`).

**Target:** typed scope keys `media/c/<chatId>/…` vs `media/g/<groupId>/…`; the server dispatches the
membership lookup to exactly one collection; **groups are minted server-side** with IDs in a namespace
disjoint from chat IDs (mirroring `/createChat`), and client `create` on `groups/*` is denied. Worker
rate limits and byte budgets key on the token's `holder` pseudonym, not the raw header. Uploader bound
into the token for overwrite/delete. Content-Type derived from the extension; `nosniff` +
`Content-Disposition: attachment`.

## 3. Duress (was two broken guarantees)

**Target:** the lock is enforced **server-side at mint** (§1). The lock decision is made **durable
before the wipe** in a wipe-preserved one-key store, drained by a boot-completed worker, so an offline
trigger still locks (`S06-H3`). The wipe prunes WorkManager and uses constant tags / no plaintext uid,
leaving no residue that a duress code was entered (`S06-H2`). `duressEligibility` is enforced
server-side in `/requestLockNonce` and in the `accountLock` create rule (`S06-M1`/`S05-M2`).

## 4. At-rest key protection

**Target:** `SecurePrefs` **fails closed** — if Keystore/EncryptedSharedPreferences init fails, it
throws rather than silently returning a plaintext-backed store, and `isInitialized()` reflects the real
state. The identity key, backup key, and SQLCipher passphrase are never written in plaintext
(`S08-H5`/`S07-M1`).

## 5. Egress

**Target:** `/linkPreview` resolves DNS, classifies every resolved address against an IPv4+IPv6
non-global blocklist, connects to the vetted address (pin), reads the body under a byte budget with a
deadline across headers+body, and **proxies** preview images through the server so no device fetches a
sender-chosen host (`S04-H1/H2/H3`, `S08-H4`).

## 6. Operator surface

**Target:** `ADMIN_TOKEN` validated for entropy at startup (boot fails below the floor); every admin
action and every auth failure durably and tamper-evidently audited; sessions have an absolute lifetime
and a binding; IP lockout normalized to IPv6 /64 (`S05-H1/H3`, `S04-M1`).

## 7. Verifiable build

**Target:** no secret in the client artifact (CI secret-scan gate); releases and tags immutable with
`SHA256SUMS`, signing-cert fingerprint, and build provenance; the vendored libsignal JAR reproducible
from the committed script and hash-asserted in CI; dependencies pinned by hash; actions pinned by SHA;
scanning + SBOM + Dependabot present (`S08-C1`, `SC-01`–`SC-10`).

## 8. Defense-in-depth (accepted with caveats)

Firebase App Check client wiring is added; enforcement is rolled out in monitoring mode first and is
**accepted** as bounded by the sideloaded-APK distribution model (`S10-N1`). Certificate pinning and
root-detection remain out of scope by the product's "compromised client is granted" threat model
(`S08-I2`, `S08-M3`).
