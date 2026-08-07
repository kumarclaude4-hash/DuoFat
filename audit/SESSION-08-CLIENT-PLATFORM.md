# Session 08 — Client Platform Hardening

_Audit session 08 of the DuoShield security review. Scope frozen at commit `9b50463` on branch
`v0/kehawed782-8894-2ab8e639`._

**Severity counts: 1 Critical / 5 High / 3 Medium / 4 Low / 3 Informational**

> 🛑 **SECOND CRITICAL OF THE AUDIT — S08-C1, and it is larger than S07-C1.**
> The release workflow writes the project's **Firebase Admin service-account private key** into
> `app/src/main/assets/` immediately before `assembleRelease`. Android packages `assets/`
> verbatim — R8 and `shrinkResources` never touch it — so the key ships inside every APK
> published to GitHub Releases. Admin SDK credentials bypass Firestore rules entirely and can
> mint a token for any uid. **This invalidates every control the previous seven sessions
> audited.** See §4.

---

## 1. Scope

Everything about the app as an Android artifact, rather than as protocol code:

| Surface | Location |
|---|---|
| Manifest posture, exported components, backup flags | `app/src/main/AndroidManifest.xml` |
| Build config, `buildConfigField` secrets, signing, R8 | `app/build.gradle` |
| Code/name-shrinking and log stripping | `app/proguard-rules.pro` |
| Network policy | `app/src/main/res/xml/network_security_config.xml` |
| Backup/transfer exclusion | `app/src/main/res/xml/data_extraction_rules.xml` |
| FileProvider grantable roots | `app/src/main/res/xml/file_paths.xml` |
| Deep links | `ui/AddContactActivity.java:154`, `:160-172` |
| Screen-capture policy | `BaseActivity.java:42-46` and every `clearFlags` call site |
| Key storage substrate | `util/SecurePrefs.java`, `db/DatabaseKeyProvider.java` |
| Local PIN gates | `util/PinManager.java`, `util/AppLockManager.java`, `LockScreenActivity.java` |
| Plaintext-at-rest on device | `DuoShieldGlideModule.java`, `util/TempFileCleaner.java`, `util/WipeHelper.java` |
| APK-embedded secrets | `.github/workflows/release.yml:55-86`, `build-release.sh`, `build-apks.sh` |

**Threat model applied.** Per `README.md` the client is assumed fully compromised, so nothing
here is scored on "an attacker with the device can read the local user's messages" — that is
granted. Findings are scored on three things instead: (a) what a **shipped artifact** hands to
an attacker who never touches a device (S08-C1, S08-H1), (b) what **other parties** —
the OS, other apps, a third-party host, a forensic examiner — learn that the product promises
they cannot (S08-H2/H3/H4/H5), and (c) whether the **duress guarantee** from Session 06
survives contact with the platform.

**Inherited from Session 07's handoff.** S07-M1 (`SecurePrefs` plaintext fallback) and S07-L2
(in-heap identity key) were deferred here for re-rating against the real platform posture;
both are settled in §5 and §8. The SQLCipher-passphrase question is answered in §5 (S08-H5).

---

## 2. Verdict

| Guarantee | Status |
|---|---|
| No secret material is compiled into or packaged with the APK | ❌ **NO** — Firebase Admin private key in `assets/` (S08-C1); `WORKER_SECRET` in `BuildConfig` (S08-H1) |
| `allowBackup` / cloud backup / device transfer are all off | ✅ **Correct** — `AndroidManifest.xml:41-43` + `data_extraction_rules.xml` exclude all five domains in both blocks |
| Release builds are non-debuggable, minified, and strip `v`/`d`/`i` logs | ✅ **Correct** — `build.gradle:110-116`, `proguard-rules.pro:150-160` |
| Cleartext HTTP is impossible | ✅ **Correct** — `usesCleartextTraffic="false"` + `base-config cleartextTrafficPermitted="false"`, and `targetSdk 34` excludes user-added CAs by default |
| Every component that is not a launcher or a deep-link target is unexported | ✅ **Correct** — one intentional `exported="true"` (`AddContactActivity`), everything else false; all three receivers and the `FileProvider` unexported |
| The app never writes to external storage | ✅ **Correct** — zero `getExternalFilesDir` / `Environment.*` call sites |
| **Message content cannot be captured by the OS or another app** | ❌ **NO** — `FLAG_SECURE` is explicitly cleared app-wide (S08-H2) |
| **Decrypted media does not persist on disk** | ❌ **NO** — 150 MB plaintext Glide disk cache + four unswept temp prefixes (S08-H3) |
| **Rendering a message discloses nothing to third parties** | ❌ **NO** — OG images fetched directly from the sender's chosen host (S08-H4) |
| **Private key material is protected by the Android Keystore** | ❌ **NO** — silent plaintext fallback, re-rated High here (S08-H5) |

The manifest and build-type posture are genuinely good — better than most apps in this
category, and visibly the product of earlier work. What fails is everything about **material
leaving the app's control**: a credential in the artifact, plaintext in the OS's caches,
plaintext in the app's own cache, and a network fetch to an attacker-chosen host on render.

---

## 3. Findings index

| ID | Severity | Title |
|---|---|---|
| S08-C1 | **Critical** | The Firebase Admin service-account private key is packaged into every released APK |
| S08-H1 | High | `WORKER_SECRET` is still compiled into `BuildConfig` and the Worker still accepts it on `/stats` |
| S08-H2 | High | `FLAG_SECURE` is deliberately *cleared* on every activity — the OS persists snapshots of plaintext chats |
| S08-H3 | High | Decrypted media persists indefinitely in `cacheDir`: a 150 MB plaintext Glide disk cache plus four unswept prefixes |
| S08-H4 | High | Link-preview images are fetched directly from the sender's chosen host — any sender gets the recipient's IP and a render timestamp |
| S08-H5 | High | `SecurePrefs`' plaintext fallback holds the identity key, backup key **and SQLCipher passphrase** (re-rate of S07-M1) |
| S08-M1 | Medium | `allowNativeHeapPointerTagging="false"` disables a memory-safety mitigation for three native libraries |
| S08-M2 | Medium | `FileProvider` declares root-scoped grantable paths, including two external roots the app never uses |
| S08-M3 | Medium | No root / tamper / hooking detection and no keystore attestation anywhere |
| S08-L1 | Low | The exported deep link accepts an unvalidated Account ID while the clipboard path validates one |
| S08-L2 | Low | Message bodies and Account IDs are copied to the clipboard without `EXTRA_IS_SENSITIVE` |
| S08-L3 | Low | The PIN length is stored beside the PIN hash, cutting the offline search space |
| S08-L4 | Low | The lock screen is layered over a live activity that has already rendered, and neither is excluded from recents |
| S08-I1 | Info | R8 is told to keep every `crypto.**` / `security.**` class *and member* name |
| S08-I2 | Info | No certificate pinning on the push server or Worker |
| S08-I3 | Info | The Worker returns `Access-Control-Allow-Origin: *` while allowing the `Authorization` header |

---

## 4. Critical

### S08-C1 — the Firebase Admin service-account private key is written into `assets/` and packaged into every published release APK

**Severity: Critical** · **Locations:**
`.github/workflows/release.yml:55-66` (the write),
`build-release.sh:10-12`, `build-apks.sh:44-45` (the same write, two more paths),
`app/build.gradle:167-183` (`packaging {}` — excludes nothing from `assets/`),
`app/src/main/assets/README.txt` (documents the location as intentional),
`.gitignore:40` (excludes it from git — and only from git)

**Trust boundary broken:** all of them. A service-account key is the credential *behind* TB-1,
TB-2, TB-3, TB-5 and TB-10 simultaneously.

#### What the code does

```yaml
# .github/workflows/release.yml:55-66
- name: Write service-account.json
  env:
    SERVICE_ACCOUNT: ${{ secrets.GOOGLE_APPLICATION_CREDENTIALS_JSON }}
  run: |
    mkdir -p app/src/main/assets
    if [ -n "$SERVICE_ACCOUNT" ]; then
      printf '%s' "$SERVICE_ACCOUNT" > app/src/main/assets/service-account.json
    else
      echo "::warning::GOOGLE_APPLICATION_CREDENTIALS_JSON not set — using stub."
      ...
```

Two steps later the same job runs `./gradlew :app:assembleRelease`, and forty lines after that
it uploads `app/build/outputs/apk/release/*.apk` to a public GitHub Release.

`app/src/main/assets/` is the Android asset source set. AAPT2 copies it into the APK **byte for
byte**:

- `minifyEnabled true` shrinks and renames *code*. Assets are not code.
- `shrinkResources true` prunes unreferenced entries under `res/`. It does not process `assets/`.
- `app/build.gradle:167-183`'s `packaging { resources { excludes += [...] } }` lists only
  `META-INF/*` entries, and `jniLibs`. Nothing excludes `assets/**`.
- There is no `androidResources { ignoreAssetsPattern }`.

So `unzip -p app-arm64-v8a-release.apk assets/service-account.json` returns the raw JSON,
including its `private_key` PEM block. `.gitignore:40` prevents the file from reaching the
*repository* — which is exactly why this reads as safe on inspection, and is why it was missed:
the leak is in the build output, not the source tree.

#### Why the key is dead weight

Nothing in `app/src/main/java/**` opens it. There is no `assets.open("service-account.json")`,
no `GoogleCredentials`, no `firebase-admin` dependency in `app/build.gradle`. The one reference
anywhere in the app source is a comment recording that it is *no longer needed*:

```java
// ChatMediaActivity.java:3325
// triggers on every new message document creation — no service-account.json
```

FCM v1 token exchange moved server-side. The asset is a leftover of the old on-device FCM
sender. Its only remaining runtime function is to publish the project's root credential.

#### Blast radius

A service-account key authenticates the **Firebase Admin SDK**, which does not evaluate
security rules at all. Anyone who downloads a release APK obtains, depending on the key's IAM
role (`firebase-adminsdk` keys are granted `roles/firebase.sdkAdminServiceAgent` by default):

- **Full read/write on all of Firestore, bypassing `firestore.rules` completely.** Every
  finding in Sessions 01, 03, 05 and 06 that is enforced by a rule — the one-way `accountLock`
  latch, the creator-only `groups/{g}/keys/{member}` slot, the `sender == request.auth.uid`
  binding, the `backups/{uid}` owner scope — stops being a control.
- **`admin.auth().createCustomToken(anyUid)`.** S07-C1 needed the victim's Account ID and got a
  session for one account at a time; this mints a session for *every* account, and does not
  need `/mintToken` at all.
- **Delivery of the exact exploit S07-H3 describes.** That finding's primary exploit path was
  "the push server's Admin SDK / a service-account compromise." This *is* that compromise, and
  it is pre-installed on the attacker's machine.
- **Read of every backup document** — which, per S07-H2, ships an unkeyed SHA-256 of its own
  plaintext, so the attacker gets a bulk offline plaintext-recovery oracle over all users at
  once.
- Firebase Storage, and any other Google Cloud API the key's roles reach.

There is no rate limit, no logging the attacker cannot see, and no client involvement. The
"zero-knowledge relay" claim in `README.md` is void for as long as any published APK exists.

#### Fix

This is an **incident**, not a bug. In order:

1. **Revoke the key now.** Google Cloud Console → IAM & Admin → Service Accounts → the
   `firebase-adminsdk-*` account → Keys → delete every key ID that has ever been in
   `GOOGLE_APPLICATION_CREDENTIALS_JSON`. Revocation is what makes published APKs harmless;
   deleting the releases is not, because they are already mirrored.
2. **Delete every published release APK** and treat all Firestore data as having been readable
   and writable by anyone since the first release built with the secret set. Audit
   `identities/**`, `users/**/public_keys/**`, `accountLock`, and `groups/**/keys/**` for
   substituted keys.
3. **Remove the write step** from `.github/workflows/release.yml:55-66`,
   `build-release.sh:10-12`, and `build-apks.sh:43-45`. The app does not read the file, so
   deleting the step is the whole change. Delete `app/src/main/assets/README.txt`, whose
   instructions cause exactly this outcome.
4. **Add a build-time guard so this cannot recur.** A `check` task that fails the build if any
   `assets/**` entry matches `"private_key"` / `"BEGIN PRIVATE KEY"` / `"service_account"`, plus
   a release-job step that runs `unzip -l` on the output APK and fails on
   `assets/service-account.json`. A negative test is the only durable control here — the
   positive one (`.gitignore`) already existed and did nothing.
5. Rotate `GOOGLE_SERVICES_JSON` too. It is *designed* to be shipped and is not a secret, but
   the same workflow handles both and the incident review should confirm which is which.

---

## 5. High

### S08-H1 — `WORKER_SECRET` is still compiled into every release APK, and the Worker still accepts it

**Severity: High** · **Locations:**
`app/build.gradle:70-77` (the `buildConfigField`),
`.github/workflows/release.yml:76`, `:85` (the value is supplied and written),
`worker/src/index.js:77-88` (`isAuthorized`), `:357-362` (`/stats` gate)

**This is a partial reversal of Session 03's SEC-A01 re-verification.** Session 03 concluded
that "the shared data-plane secret is genuinely gone from the app's runtime." That is
*precisely* true and remains true — `B2StorageHelper` never reads `BuildConfig.WORKER_SECRET`;
every data-plane call uses a per-object capability token. But the build still bakes the value in:

```groovy
// app/build.gradle:75-77
def workerSecret = (localProps.getProperty('worker.secret', '') ?: '').trim()
if (workerSecret.isEmpty()) workerSecret = (System.getenv('WORKER_SECRET') ?: '').trim()
buildConfigField "String", "WORKER_SECRET", "\"${workerSecret}\""
```

```yaml
# .github/workflows/release.yml:76, :85
  WORKER_SECRET:      ${{ secrets.WORKER_SECRET }}
  echo "worker.secret=${WORKER_SECRET:-}"   >> local.properties
```

`buildConfigField` emits a `public static final String` into `BuildConfig.java`. R8 constant-folds
it, which does not remove it — it *inlines the literal into the DEX string pool*, where
`strings` or any decompiler finds it. The B2 credentials directly above it were correctly
neutralized to `"\"\""` with an explicit "F9 fix: B2 credentials removed from APK" comment;
`WORKER_SECRET` was left live.

**Exploit path.** Extract the string from any release APK, then:

```
GET https://<worker>/stats
Authorization: Bearer <WORKER_SECRET>
→ 200  { r2: {used_bytes…}, b2: {used_bytes…}, requests: {today_approx…} }
```

`/stats` is the one endpoint still gated by the shared secret and is documented as
"operator-only" (`worker/src/index.js:357-360`). The disclosure itself is modest — aggregate
storage and request counters, which are a coarse traffic-analysis signal for a metadata-resistant
product and nothing worse. The severity is driven by two other things: the secret is
**the same value** whose compromise SEC-A01 was filed to fix, so any future endpoint that reuses
`isAuthorized` is open by default; and it is a live, un-rotatable-per-install credential in a
public artifact, which makes every existing release permanently untrustworthy for that check.

**Fix.** Delete the `buildConfigField` (follow the B2 precedent at `:48-49` — hard-code `""` or
remove the field outright) and remove `WORKER_SECRET` from `release.yml:76`/`:85`. Then rotate
the Worker secret with `wrangler secret put WORKER_SECRET`, and move `/stats` off the shared
bearer entirely — it is an operator view, so it belongs behind the same admin auth as
`server/index.js`'s `/admin/*` (subject to S05-H1's entropy problem) or behind Cloudflare Access.

---

### S08-H2 — `FLAG_SECURE` is explicitly cleared on every activity, so the OS persists snapshots of plaintext chats

**Severity: High** · **Locations:**
`BaseActivity.java:42-46`, `MainActivity.java:41`, `LockScreenActivity.java:68`,
`ui/SecurityPrivacySettingsActivity.java:336-337`,
and the two stale comments that assert the opposite: `ChatMediaActivity.java:1348`,
`GroupChatActivity.java:200`

```java
// BaseActivity.java:42-46
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    // Screenshots are always allowed — FLAG_SECURE is not applied globally.
    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
}
```

This is not an omission — it is an active, repeated clear on five paths, including one on
`LockScreenActivity` itself. Two comments in the chat activities claim the flag "is now applied
globally in BaseActivity", which is the exact inverse of what `BaseActivity` does; a maintainer
reading `ChatMediaActivity.java:1348` will conclude the protection exists.

**What `FLAG_SECURE` actually gates**, all of which is therefore enabled:

1. **Recents/task snapshots.** Android writes a bitmap of the top activity every time it goes to
   background, into `/data/system_ce/<user>/snapshots/`. That is **outside the app's sandbox**,
   is not covered by `allowBackup="false"`, is not deleted by `WipeHelper`, and survives an
   uninstall until the system prunes it. Since the app renders decrypted messages, this is a
   plaintext chat screenshot at OS level, refreshed on every backgrounding.
2. **`MediaProjection` and accessibility capture.** Any other installed app with screen-record
   or accessibility permission — the standard spyware posture on a "borrowed" phone — captures
   chat content live.
3. **Manual screenshots**, which land in shared media storage readable by anything with
   `READ_MEDIA_IMAGES`.

**Interaction with S08-L4 and Session 06.** The PIN lock starts `LockScreenActivity` from
`BaseActivity.onStart()` (`:117-124`) *without* finishing the underlying activity, so the
conversation has already been laid out and drawn before the lock appears — the snapshot the
system captured on the way to background is of the unlocked screen. And the duress feature's
promise (`SESSION-06-DURESS.md`) is that after a wipe there is no evidence a duress code was
used; an OS-level snapshot of the pre-wipe conversation list, outside the app's reach, defeats
that independently of S06-H2's WorkManager residue.

**Fix.** Set the flag instead of clearing it, in `BaseActivity.onCreate`:

```java
getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                     WindowManager.LayoutParams.FLAG_SECURE);
```

and delete the four other `clearFlags` call sites. If screenshots must remain a user-facing
option, gate the *clear* on an explicit opt-in preference that defaults to off, and never apply
it to `LockScreenActivity`, `SeedPhraseDisplayActivity`, `RestoreFromSeedActivity`,
`KeyFingerprintActivity`, or `ManageUnlockCodesActivity`. On API 33+ also call
`setRecentsScreenshotEnabled(false)`, which addresses (1) even where the window flag is relaxed.
Fix the two lying comments in the same commit.

---

### S08-H3 — decrypted media persists indefinitely in `cacheDir`: a 150 MB plaintext Glide disk cache plus four unswept temp prefixes

**Severity: High** · **Locations:**
`DuoShieldGlideModule.java:59-63` (the disk cache),
`ui/MessageAdapter.java:740`, `:996`, `:587`, `FullScreenImageActivity.java:93` (plaintext fed to Glide),
`util/TempFileCleaner.java:120-131` (`isTempMediaFile`, the allowlist),
`ChatMediaActivity.java:2459`, `GroupChatActivity.java:830-831`, `util/B2StorageHelper.java:783` (the unswept writers)

The app decrypts media in memory and hands the plaintext bytes to Glide:

```java
// ui/MessageAdapter.java:740
Glide.with(ctx).load(plainBytes).centerCrop().into(h.imageView);
```

No call site anywhere sets `diskCacheStrategy`. Glide's default is `AUTOMATIC`, which for a
local/in-memory data source writes the **decoded resource** to the disk cache — and that cache is
explicitly configured, enlarged, and pointed into `cacheDir`:

```java
// DuoShieldGlideModule.java:61-63
builder.setDiskCache(new DiskLruCacheFactory(
        () -> new java.io.File(context.getCacheDir(), "glide_image_cache"),
        diskCacheMb * MB));                       // 150 MB, or 75 MB on low-RAM
```

The result is up to 150 MB of decrypted chat images sitting in
`/data/data/com.duoshield.app/cache/glide_image_cache/`, evicted only by LRU pressure — i.e. in
practice, never, for a normal user. This is the E2EE equivalent of storing the plaintext next to
the ciphertext, and it is the same class of mistake as S07-H2 (a plaintext derivative shipped
beside an encrypted blob), just on the device instead of the server.

**`TempFileCleaner` does not cover it, and misses three more writers.** The worker exists
specifically to sweep decrypted media (`TempFileCleaner.java:17-21`), but it iterates
`cacheDir.listFiles()` against a fixed allowlist (`:120-131`) and `continue`s on anything
unmatched:

| Written by | Prefix | Contents | Swept? |
|---|---|---|---|
| `ChatMediaActivity.java:1235`, `MediaViewerActivity.java:81` | `voice_`, `vid_` | decrypted audio/video | ✅ 5 min |
| `util/SecureShareHelper.java:27` | `share_` | decrypted image | ✅ 5 min |
| `util/ChatExportHelper.java:202` | `DuoShield_Export_` | plaintext transcript ZIP | ✅ 24 h |
| **`DuoShieldGlideModule.java:62`** | `glide_image_cache/` | **decrypted images, up to 150 MB** | ❌ **never** |
| **`ChatMediaActivity.java:2459`** | `cam_*.jpg` | camera capture, pre-encryption | ❌ **never** |
| **`GroupChatActivity.java:830`** | `grp_cam_*.jpg` | camera capture, pre-encryption | ❌ **never** |
| **`util/B2StorageHelper.java:783`** | `thumb_*.mp4` | video thumbnail source | ❌ **never** |

The directory branch at `:63-71` only matches `chat_export_*`, so `glide_image_cache` falls
through as an unmatched directory. `enc_*.tmp` and `b2dl_*.enc` are correctly *not* swept —
those are ciphertext.

`WipeHelper.java:103-106` does `deleteDir(ctx.getCacheDir())` recursively, so the duress and
Danger-Zone wipes do clear all of it — that bounds the finding to "before a wipe", which is the
entire normal lifetime of the install, and does not help against a device seized without warning.
Note the files are unlink-only (`f.delete()`), with no overwrite, so recovery from unallocated
flash pages remains possible after a wipe.

**Fix.**

1. Stop caching plaintext: `.diskCacheStrategy(DiskCacheStrategy.NONE)` on every `load()` that
   receives decrypted bytes, or — better and harder to regress — set it once as a default via
   `builder.setDefaultRequestOptions(new RequestOptions().diskCacheStrategy(NONE))` in
   `DuoShieldGlideModule.applyOptions`, then opt *in* only for `R.drawable.*` resources. The
   memory cache is fine and should stay.
2. Delete `cacheDir/glide_image_cache` on upgrade to clear existing installs' residue.
3. Invert `TempFileCleaner`'s allowlist into a **denylist**: sweep everything in `cacheDir`
   older than the age threshold *except* the known-ciphertext prefixes. An allowlist silently
   fails to cover the next writer someone adds, which is exactly what happened four times here.
4. Add `cam_`/`grp_cam_` deletion to the send-completion path rather than waiting on the worker,
   and delete `thumb_*.mp4` in `B2StorageHelper` once the thumbnail is encrypted.

---

### S08-H4 — link-preview images are fetched directly from the sender's chosen host, handing any sender the recipient's IP address and a render timestamp

**Severity: High** · **Locations:**
`ui/MessageAdapter.java:890-896` (the direct fetch),
`util/LinkPreviewFetcher.java:110` (the proxy that is bypassed),
`server/index.js` `/linkPreview` (the SSRF-hardened path from Session 04)

Session 04 audited `/linkPreview` as a server-side fetcher precisely so the *server* — not the
user's device — makes the outbound request. The metadata half of that design holds. The image
half does not:

```java
// ui/MessageAdapter.java:890-896
if (preview.imageUrl != null && !preview.imageUrl.isEmpty()) {
    h.linkPreviewImage.setVisibility(View.VISIBLE);
    Glide.with(ctx).load(preview.imageUrl)          // ← device → arbitrary third-party host
         .centerCrop()
         .placeholder(android.R.drawable.ic_menu_gallery)
         .into(h.linkPreviewImage);
```

`preview.imageUrl` is the `og:image` URL harvested from a page the **sender** chose. The fetch
happens in `onBindViewHolder` — automatically, on scroll, with no tap and no confirmation.

**Exploit path.** The attacker needs only to be able to send the victim a message, which in a
messenger is the baseline capability:

```
1. Attacker hosts https://evil.example/p/<nonce> returning
   <meta property="og:image" content="https://evil.example/i/<nonce>.jpg">
2. Attacker sends that URL to the victim.
3. Victim's client calls /linkPreview  → the SERVER fetches the page (correct, per S04)
   and returns {title, imageUrl: "https://evil.example/i/<nonce>.jpg"}.
4. MessageAdapter renders the row → the DEVICE fetches /i/<nonce>.jpg directly.
5. Attacker's access log: victim's source IP, User-Agent, TLS fingerprint, exact timestamp.
```

Because `<nonce>` is per-recipient, this is an unconsented, unblockable **web bug**: a read/render
receipt and an IP-geolocation primitive against a specific user, repeatable at will, and
effective inside a group (one message, every member's IP). For a product whose stated value is
metadata resistance — and which routes TURN, media, and preview metadata through its own
infrastructure specifically to avoid this — device-level IP disclosure to an attacker-chosen host
is a first-order failure, not a cosmetic one. It also silently defeats any Tor/VPN assumption a
user might reasonably hold about the app.

Note that `network_security_config.xml` does not mitigate this: `https://` passes the cleartext
policy, and the fetch is to a real, valid host.

**Fix.** Never let the device dereference a sender-supplied URL. Extend `/linkPreview` to fetch,
validate (content type, dimensions, a hard byte cap), re-encode, and **return the image bytes**
— or cache it under a server-owned key and return that URL. The existing
`fetchFollowingSafeRedirects` / `isBlockedPreviewHost` guard already belongs on this hop, and
S04-H1's DNS/IPv6 gap in that predicate must be fixed for it to mean anything. Until the proxy
ships, do not render `og:image` at all: a title-and-domain-only preview leaks nothing.
`MessageAdapter.java:1005` (`load(Uri.parse(url))`) needs the same review.

---

### S08-H5 — `SecurePrefs`' plaintext fallback holds the identity key, the backup key, and the SQLCipher passphrase (re-rate of S07-M1 to High)

**Severity: High** (re-rated from Medium — this session owns the call, per Session 07's handoff) ·
**Locations:** `util/SecurePrefs.java:135-174`, `db/DatabaseKeyProvider.java:53`, `:72-76`,
`crypto/signal/SignalKeyManager.java:261-278`, `app/build.gradle:22` (`minSdk 26`)

Session 07 found the three-tier `EncryptedSharedPreferences` init falling through to plaintext
(`SecurePrefs.java:170-174`) and rated it Medium, deferring the platform question here. The
platform answer cuts both ways, and on balance raises it.

**What the platform genuinely mitigates** — this is real, and it is why the rating is High and
not Critical:

- `android:allowBackup="false"` + `android:fullBackupContent="false"`
  (`AndroidManifest.xml:41-42`) block ADB backup and the legacy cloud path.
- `data_extraction_rules.xml` excludes `sharedpref`, `database`, `file`, `root` and `external`
  under **both** `<cloud-backup>` and `<device-transfer>` — so neither Google backup nor a
  phone-to-phone transfer moves the file.
- `debuggable false` (`build.gradle:113`) blocks `run-as`/JDWP heap access, which also bounds
  S07-L2's in-heap key retention.
- `extractNativeLibs="false"` keeps native code mmapped from the APK.

Together these mean the plaintext file is reachable only by a root/forensic adversary — not by
another installed app, and not over ADB on a locked bootloader.

**Why it is nonetheless High:**

1. **The SQLCipher passphrase is in that store.** `DatabaseKeyProvider.KEY_DB_CIPHER`
   (`:53`) is read through `SecurePrefs.get(appCtx)` (`:73`). SQLCipher's entire purpose is to
   make the message database unreadable on a seized device; the passphrase sitting in a plaintext
   `MODE_PRIVATE` XML file in the same data directory reduces full-database encryption to
   file-permission isolation. Session 07's handoff asked this question directly — the answer is
   that the database's confidentiality is exactly the store's confidentiality, with no
   independent margin.
2. **A root/forensic adversary is the product's stated adversary.** DuoShield ships a duress PIN,
   a shake-to-lock, an auto-wipe, and a remote account lock. Those features only make sense
   against someone holding the device. `SESSION-06-DURESS.md`'s deniability guarantee and
   S06-I3's dependency on hardware backing both fail here, and the fallback also holds the
   backup AES key — which, per S07-H2, unlocks a server-side plaintext-recovery oracle for the
   *entire* history rather than just what is on the phone.
3. **Nothing observes or reports the degradation.** `isAvailable()` exists but
   `SignalKeyManager.isInitialized()` documents at length that it must not gate on it
   (`:261-278`) — correct, since gating caused a sign-in loop — so the app cannot tell the user,
   and telemetry cannot tell the operator, that a given install is running unprotected. There is
   also no lower bound: tiers 2 and 3 deliberately pass `setIsStrongBoxBacked(false)` and skip
   `setUserAuthenticationRequired`, and `minSdk 26` predates any attestation requirement, so
   even a "successful" init may be a software key.
4. The javadoc's rationale — "the same level of protection WhatsApp and Telegram use on devices
   without a hardware TEE" — is a reasonable *availability* trade-off and the wrong comparison
   for a product marketed on duress resistance.

**Fix.** Keep the fallback (blocking sign-in is worse), but stop it from being silent and stop
it from covering the database:

1. Persist the achieved tier and surface it: a non-dismissable banner in
   `SecurityPrivacySettingsActivity` when `isAvailable()` is false, and a one-line
   `securityLevel` field on the user's own document so the operator can measure how many installs
   land there.
2. Derive the SQLCipher passphrase from a key that never exists at rest — e.g. PBKDF2 over the
   device-gate PIN with a random stored salt — so the plaintext file holds a salt rather than the
   passphrase itself. Note this changes the durability contract at
   `DatabaseKeyProvider.java:21-45`; that contract is well reasoned and the migration must
   preserve it.
3. When `isAvailable()` is false, refuse to write the **backup** key at all and disable cloud
   backup for that install. Local messages are already exposed on such a device; the backup key
   extends the exposure to the full server-side history and is the one item worth failing closed
   on.
4. Add `setUserAuthenticationRequired(true)` with a generous validity window to tier 1 only,
   keeping tier 2 as the compatibility path, so the common case gains keyguard binding without
   reintroducing the budget-device failure.

---

## 6. Medium

### S08-M1 — `allowNativeHeapPointerTagging="false"` disables a memory-safety mitigation for three native libraries

**Severity: Medium** · **Location:** `AndroidManifest.xml:55`

```xml
android:allowNativeHeapPointerTagging="false"
```

Pointer tagging (Android 11+, arm64 TBI) makes a large class of heap use-after-free and
buffer-overflow bugs crash deterministically instead of corrupting memory silently, and it is
the on-ramp to MTE on hardware that supports it. It exists as an opt-out solely for apps whose
native code stashes metadata in the top byte of pointers.

This process loads three substantial native libraries that parse attacker-controlled bytes:
libsignal's Rust core (`org.signal:libsignal-android:0.54.1`), WebRTC
(`io.getstream:stream-webrtc-android:1.1.1` — media parsing from a remote peer), SQLCipher
(`net.zetetic:android-database-sqlcipher:4.5.4`), plus ExoPlayer's decoders. Turning the
mitigation off for the whole process to satisfy one library — with no comment saying which one,
unlike every other flag in this manifest — removes exploit-mitigation depth exactly where
untrusted input meets native code.

**Fix.** Delete the attribute and test. If a library genuinely faults, identify it, file it
upstream, and record the specific reason inline; `false` should never be an unexplained default.

### S08-M2 — `FileProvider` declares root-scoped grantable paths, including two external roots the app never uses

**Severity: Medium** · **Location:** `app/src/main/res/xml/file_paths.xml:3-6`

```xml
<cache-path          name="cache"          path="." />
<external-cache-path name="external-cache" path="." />
<files-path          name="files"          path="." />
<external-files-path name="external_files" path="." />
```

Four roots, each scoped to `.` — the whole directory. Only three files are ever shared
(`ChatMediaActivity.java:2460`, `GroupChatActivity.java:832`, `util/SecureShareHelper.java:35`,
`util/ChatExportHelper.java:376`), all of them in `cacheDir`, and the app makes zero use of
external storage (no `getExternalFilesDir` call sites anywhere).

The provider is `exported="false"` and `FileProvider` canonicalizes paths, so this is not
directly traversable — a grant still has to be issued per URI. The exposure is that *any*
`Uri` the app hands out becomes addressable within a root that also contains, per S08-H3, the
plaintext Glide cache and the plaintext export ZIP; a single over-broad or long-lived grant
(clipData, a persisted grant, an intent forwarded by a share target) widens from one file to a
directory of plaintext. Least privilege costs nothing here.

**Fix.** Write to dedicated subdirectories and declare only those:
`<cache-path name="shared" path="shared/" />`, with camera captures under `shared/camera/` and
exports under `shared/export/`. Delete both external roots.

### S08-M3 — no root, tamper, hooking, or emulator detection, and no keystore attestation

**Severity: Medium** · **Locations:** absent — the only match for any related term in
`app/src/main/java/**` is `util/LogRedact.java:17`, which is unrelated

The threat model correctly says client-side checks are never *controls*, and this finding does
not dispute that. It matters for a narrower reason: several of this app's advertised features
are **local** promises with no server counterpart, and integrity signals are the only thing that
can inform them.

- Nothing detects Frida/Xposed, so `PinManager`'s PBKDF2 verify, the duress-PIN comparison, and
  the local lock timer can be hooked and returned `true` — and the app cannot know.
- Nothing detects root, so S08-H5's plaintext fallback and S08-H3's plaintext cache are silently
  readable with no signal to the user.
- `SecurePrefs` never calls `KeyInfo.isInsideSecureHardware()` (API 23+) or requests key
  attestation, so the app cannot distinguish tier 1 hardware from tier 2 software — which is the
  measurement S08-H5 needs and the direct answer to S06-I3.
- No `PackageManager` signature check, so a repackaged APK — trivially producible given S08-C1
  and the absence of any signing-cert pin — is indistinguishable from the real one.

**Fix.** In priority order: (1) `KeyInfo.isInsideSecureHardware()` in `SecurePrefs`, recorded and
surfaced — cheap, reliable, and it settles S08-H5's measurement problem; (2) a signing-certificate
digest check at startup against a compiled-in SHA-256; (3) Play Integrity or SafetyNet
attestation reported to the server so operators can *observe* the population, with any local
degradation limited to warnings. Do not gate functionality on any of these — a false positive
locks a legitimate user out of their messages.

---

## 7. Low

### S08-L1 — the exported deep link accepts an unvalidated Account ID while the clipboard path validates one

**Location:** `ui/AddContactActivity.java:154`, `:160-172`; `AndroidManifest.xml:128-142`

`AddContactActivity` is the app's single `exported="true"` non-launcher component, reachable from
any app or any web page via `duoshield://add/<userId>`. `handleDeepLink` takes
`data.getPath()`, strips the leading `/`, and calls `setText` on it with no validation — while
`tryPasteFromClipboard` twenty lines below applies a strict regex to the same field:

```java
// :167  deep link — no validation
String userId = path.startsWith("/") ? path.substring(1) : path;
if (!userId.isEmpty() && etPartnerUserId != null) etPartnerUserId.setText(userId);

// :186  clipboard — validated
if (s.matches("[23456789A-HJ-NP-Z]{5}-[23456789A-HJ-NP-Z]{5}-[23456789A-HJ-NP-Z]{3}")) { … }
```

Impact is limited: the value is prefilled, not acted on — the user must still tap through — and
the class extends `BaseActivity`, so the lock gate in `onStart` applies. It is a UI-spoofing and
social-engineering surface (a long or homoglyph-bearing string in a field the user is about to
confirm), not an authorization bypass. Recorded because the correct check already exists in the
same file and the asymmetry will look like an oversight to the next reader.

**Fix.** Apply the same regex in `handleDeepLink`, drop non-matching input silently, and call it
from `onNewIntent` as well as `onCreate`.

### S08-L2 — message bodies and Account IDs are copied to the clipboard without `EXTRA_IS_SENSITIVE`

**Locations:** `ChatMediaActivity.java:2092-2093`, `:2882-2886`, `ui/AddContactActivity.java:210`,
`ui/MessageAdapter.java:694-697`, `util/ClipboardHelper.java:40-48`

Android's clipboard is global. On API 33+ the system shows a preview toast of copied text and,
absent `ClipDescription.EXTRA_IS_SENSITIVE`, renders it in the clear; the content also stays
readable to the foreground app and to IMEs until replaced. `ClipboardHelper` does implement a
delayed self-clear (`:48`), which is the right instinct, but the four direct
`setPrimaryClip(ClipData.newPlainText(...))` call sites bypass it and none of the five set the
sensitivity flag.

**Fix.** Route every copy through `ClipboardHelper`, and set
`clip.getDescription().getExtras().putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)` there.
Keep the timed clear.

### S08-L3 — the PIN length is stored beside the PIN hash, cutting the offline search space

**Locations:** `util/PinManager.java:43`, `:45`, `:55`

`ITERATIONS = 310_000` with PBKDF2-HMAC-SHA256 and a random salt is a genuinely good choice —
above the OWASP floor. But `KEY_LEN_PREFIX = "app_pin_length_"` and
`KEY_DEVICE_PIN_LEN` persist the exact PIN length in the same store, so an adversary who reaches
the hash (which per S08-H5 may be a plaintext file) knows to enumerate exactly 10⁶ candidates
for a 6-digit PIN rather than a range. At 310k iterations that is roughly a GPU-day — slow, but
finite, and the length hint removes the only uncertainty.

The PIN is not a key (it gates the UI; SQLCipher and Signal keys are independent), so the impact
is bounded to defeating the local lock and the duress gate. Recorded because the length field
buys UI convenience at a real cryptographic cost.

**Fix.** Do not persist the length — infer it from the entered value at verify time, or store it
only in the non-secure `duoshield_prefs`. Better, allow alphanumeric passphrases so the search
space is not 10⁶ by construction, and add a hard failed-attempt lockout so an *online* attack
against the gate is bounded regardless.

### S08-L4 — the lock screen is layered over a live activity that has already rendered, and neither is excluded from recents

**Locations:** `BaseActivity.java:116-127`, `:150-157`, `AndroidManifest.xml:91-95`

`shouldLock()` fires in `onStart`, after `onCreate` has inflated and populated the underlying
activity, and `LockScreenActivity` is started with `FLAG_ACTIVITY_CLEAR_TOP` **without**
`finish()` on the caller. The chat is therefore drawn, then covered. `LockScreenActivity` has no
`android:excludeFromRecents`, no `noHistory`, and no `singleInstance`, and clears `FLAG_SECURE`
itself (`:68`).

Alone this is a small ordering defect — the OS still requires the PIN before handing control back.
Combined with S08-H2 it is the mechanism by which the recents snapshot ends up depicting the
unlocked screen rather than the lock screen.

**Fix.** Decide the lock in `onCreate` before inflating, or render an opaque overlay immediately
in `onCreate` and remove it only after the lock resolves. Add
`android:excludeFromRecents="true"` and `android:launchMode="singleTask"` to
`LockScreenActivity`, and set `FLAG_SECURE` on it rather than clearing it.

---

## 8. Informational

### S08-I1 — R8 is told to keep every `crypto.**` / `security.**` class *and member* name

`proguard-rules.pro:12-16` applies `-keep class … { *; }` plus `-keepclassmembers` to
`com.duoshield.app.crypto.**` and `com.duoshield.app.security.**`. Obfuscation is not a security
control and the threat model says so — but these two packages are the ones an attacker reverse-
engineering the APK most wants a map of, and the keep rules hand over
`SeedPhraseHelper.deriveUserId`, `BackupCryptoHelper` labels, `DuressManager`, and
`SignalKeyManager` with original names and signatures. The stated reasons (Room, Firestore
reflection) apply to `models.**`, not to these; the crypto classes are invoked directly from
app code and do not need reflective access.

Worth noting alongside it: `-assumenosideeffects` on `Log.v/d/i` (`:150-160`) is a well-reasoned
and correctly-implemented control, and `-keepattributes SourceFile,LineNumberTable` with
`-renamesourcefileattribute` is the right crash-reporting compromise. Narrow the crypto keeps to
`-keepclassmembers` on the specific entry points that genuinely need them, and let R8 rename the
rest.

### S08-I2 — no certificate pinning on the push server or the Worker

`network_security_config.xml` sets `cleartextTrafficPermitted="false"` and nothing else. With
`targetSdk 34` the default trust anchors already exclude user-added CAs, so the common
mitm-proxy case is handled; what remains unpinned is a mis-issuance by a public CA against
`duofat.onrender.com` or `*.workers.dev`. Every credential the app sends over those channels
(Firebase ID tokens, capability tokens, the duress nonce) would be exposed to such an attacker.

Recorded as Info rather than a finding because the threat model already grants the attacker the
client, and because pinning a Render/Cloudflare hostname carries a real availability risk on
certificate rotation. If it is adopted, pin the *intermediate* CA with a backup pin and a
`expiration` date, not a leaf.

### S08-I3 — the Worker returns `Access-Control-Allow-Origin: *` while allowing `Authorization`

`worker/src/index.js:60-64` sets `Access-Control-Allow-Origin: '*'` with
`Access-Control-Allow-Headers: 'Authorization, Content-Type, X-Client-ID'`. Because the origin is
a wildcard, browsers refuse to send credentials, and the data plane requires a per-object
capability token that no browser holds — so there is no CSRF path today. It is recorded because
the combination reads as credential-bearing CORS and will be copied as a template; narrowing to
the app's actual origins (or dropping CORS entirely, since the client is native) removes the
ambiguity.

---

## 9. Re-ratings and inherited items

| Item | Origin | Resolution in this session |
|---|---|---|
| **S07-M1** — `SecurePrefs` plaintext fallback | Session 07 (deferred here) | **Re-rated Medium → High** as S08-H5. The platform mitigations are real and bound it to a root/forensic adversary (`allowBackup=false`, both `data-extraction-rules` blocks, `debuggable=false`) — but that adversary *is* the duress feature's adversary, and the store holds the SQLCipher passphrase, so full-database encryption inherits the fallback's strength exactly. |
| **The SQLCipher passphrase question** | Session 07 handoff | **Answered: the database's confidentiality equals the store's, with no independent margin.** `DatabaseKeyProvider.java:53`,`:73`. Folded into S08-H5 with a derive-from-PIN fix. |
| **S07-L2** — identity key retained in the process heap | Session 07 (deferred here) | **Confirmed Low, and bounded.** `debuggable false` (`build.gradle:113`) blocks `run-as`/JDWP heap dumps and `allowBackup="false"` blocks ADB backup, so extraction needs root or a kernel bug. The static `derivationCache` should still be cleared on duress wipe. |
| **S06-I3** — is `SecurePrefs` hardware-backed? | Session 06 → 07 | **Closed: no**, and this session adds *why the app cannot tell* — no `KeyInfo.isInsideSecureHardware()` call anywhere (S08-M3). Session 06's deniability claim needs an explicit device-class caveat. |
| **SEC-A01** (`WORKER_SECRET`) | Prior review → Session 03 | **Downgraded from "partially remediated" to "partially remediated, and still leaking."** Session 03's runtime finding was exactly right; the *build* still bakes the secret into every APK and the Worker still accepts it on `/stats` (S08-H1). Third distinct defect under this one ID — carry all three into Session 10. |
| **S04 `/linkPreview` SSRF hardening** | Session 04 | **Scope narrowed.** The server-side fetch that Session 04 audited covers only the page metadata; the `og:image` is fetched by the device (S08-H4), so the proxy's privacy benefit does not extend to the image hop at all. |
| **S07-H2** (backup plaintext oracle) | Session 07 | **Amplified by S08-C1.** S07-H2's reader was "the Firestore operator, or via S07-C1 any user who knows the Account ID." With the Admin key in the APK it is *anyone who downloaded a release*, over all users at once. |
| **Deep links vs. seed-handling activities** | Session 07 handoff | **Verified clean.** `RestoreFromSeedActivity` and `KeyFingerprintActivity` are both `exported="false"` with no intent filters; the only exported non-launcher component is `AddContactActivity` (S08-L1). |

---

## 10. Recommended fix order

1. **S08-C1** — revoke the service-account key, pull the releases, delete the three write steps,
   add the negative build check. This is incident response and it precedes everything, including
   S07-C1: fixing `/mintToken` is pointless while the Admin key is public.
2. **S08-H1** — delete the `buildConfigField`, rotate the Worker secret. Same commit as C1; it is
   the same class of defect and the same file.
3. **S08-H2** — set `FLAG_SECURE` instead of clearing it. A one-line inversion that closes an
   OS-level plaintext leak outside the app's reach, and it repairs Session 06's deniability claim.
4. **S08-H4** — stop rendering `og:image` immediately; proxy it properly in the same release as
   S04-H1's host-predicate fix.
5. **S08-H3** — `DiskCacheStrategy.NONE` by default, then invert `TempFileCleaner` to a denylist.
6. **S08-H5** — surface the tier (with `KeyInfo.isInsideSecureHardware()` from S08-M3), then
   re-derive the SQLCipher passphrase and fail closed on the backup key.
7. **S08-M1, S08-M2**, then the Lows. S08-M3's attestation work is a prerequisite for measuring
   S08-H5 and should be scheduled with it.

## 11. Session 09 handoff

Session 09 (supply chain & CI/CD) inherits:

- **S08-C1 is a CI defect as much as an APK defect.** The write step lives in
  `.github/workflows/release.yml`, and the same code is duplicated in `build-release.sh` and
  `build-apks.sh`. Session 09 owns the question of *why a release job has that secret in scope at
  all*, and whether the job's `permissions:`/secret surface should be split.
- **S08-H1 likewise** — `release.yml:76`,`:85` supplies `WORKER_SECRET` to a build that must not
  have it.
- **The `assets/` packaging blind spot** — no step inspects the built APK before publishing.
  Session 09 should decide what an artifact-inspection gate looks like in general, not just for
  this one filename.
- **`app/libs/libsignal-client-0.54.1-stripped.jar`** — the app's entire cryptographic library is
  a hand-modified binary committed to the repo, produced by `scripts/strip_signal_records.py`,
  which does no checksum verification of its input. Its provenance is Session 09's problem, and
  `proguard-rules.pro:95-98` keeps it verbatim in the APK.
- **`app/build.gradle:88-105`** — the `signingConfigs.release` block silently produces an
  unsigned-or-mis-signed "release" when `KEY_ALIAS`/`KEY_PASSWORD` are absent, because the whole
  config is wrapped in `if (ksFile.exists() && ksPwd != null)`.
