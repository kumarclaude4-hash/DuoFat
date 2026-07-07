#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# DuoShield — Build Debug + Release APKs
# Run from repo root. Requires env vars set as Replit Secrets:
#   GOOGLE_SERVICES_JSON               (always required)
#   GOOGLE_APPLICATION_CREDENTIALS_JSON (always required)
#   KEYSTORE_BASE64                    (release only)
#   KEYSTORE_PASSWORD                  (release only)
#   KEY_PASSWORD                       (release only)
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
ANDROID_HOME=/home/runner/android-sdk
export ANDROID_HOME

echo "══════════════════════════════════════════════"
echo "  DuoShield APK Build"
echo "══════════════════════════════════════════════"

# ── 1. Android SDK ────────────────────────────────
echo ""
echo "▶ Step 1: Android SDK"
bash "$ROOT/setup-android-sdk.sh"

# ── 2. Secret files ───────────────────────────────
echo ""
echo "▶ Step 2: Writing secret files"

python3 - << 'PY'
import os, sys
val = os.environ.get("GOOGLE_SERVICES_JSON", "")
if not val:
    print("ERROR: GOOGLE_SERVICES_JSON not set"); sys.exit(1)
open("app/google-services.json", "w").write(val)
print("  ✅ app/google-services.json written")
PY

python3 - << 'PY'
import os, sys
val = os.environ.get("GOOGLE_APPLICATION_CREDENTIALS_JSON", "")
if not val:
    print("ERROR: GOOGLE_APPLICATION_CREDENTIALS_JSON not set"); sys.exit(1)
open("app/src/main/assets/service-account.json", "w").write(val)
print("  ✅ app/src/main/assets/service-account.json written")
PY

# ── 3. Debug APK ──────────────────────────────────
echo ""
echo "▶ Step 3: Building Debug APK"
"$ROOT/gradlew" :app:assembleDebug --no-daemon
echo ""
echo "  ✅ Debug APK:"
ls -lh "$ROOT/app/build/outputs/apk/debug/"*.apk 2>/dev/null || echo "  (no debug APK found)"

# ── 4. Release APK (requires keystore secrets) ────
echo ""
echo "▶ Step 4: Building Release APK"

if [ -z "${KEYSTORE_BASE64:-}" ]; then
  echo "  ⚠️  KEYSTORE_BASE64 not set — skipping release build."
  echo "     Add KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_PASSWORD as Replit Secrets to enable."
  echo ""
  echo "══════════════════════════════════════════════"
  echo "  Build complete (Debug only)"
  echo "══════════════════════════════════════════════"
  exit 0
fi

python3 - << 'PY'
import os, base64, sys
ks = os.environ.get("KEYSTORE_BASE64", "")
if not ks:
    print("ERROR: KEYSTORE_BASE64 empty"); sys.exit(1)
with open("app/duoshield-release.keystore", "wb") as f:
    f.write(base64.b64decode(ks))
print("  ✅ app/duoshield-release.keystore written")
PY

"$ROOT/gradlew" :app:assembleRelease --no-daemon
echo ""
echo "  ✅ Release APK:"
ls -lh "$ROOT/app/build/outputs/apk/release/"*.apk 2>/dev/null || echo "  (no release APK found)"

echo ""
echo "══════════════════════════════════════════════"
echo "  Build complete (Debug + Release)"
echo "══════════════════════════════════════════════"
