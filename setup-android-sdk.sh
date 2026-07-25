#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# DuoShield — Android SDK Bootstrap
# Installs command-line tools + required SDK packages into /home/runner/android-sdk
# Safe to re-run: skips downloads if already present.
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

ANDROID_HOME=/home/runner/android-sdk
export ANDROID_HOME

CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
CMDLINE_TOOLS_ZIP="/tmp/cmdline-tools.zip"
CMDLINE_TOOLS_DIR="$ANDROID_HOME/cmdline-tools"

# ── 1. Early exit if SDK already fully installed ──────────────────────────────
if [ -d "$ANDROID_HOME/platforms/android-34" ] && \
   [ -d "$ANDROID_HOME/build-tools" ] && \
   [ "$(ls "$ANDROID_HOME/build-tools" 2>/dev/null | head -1)" != "" ]; then
  echo "✅ Android SDK already installed at $ANDROID_HOME"
  # Ensure local.properties is in place
  echo "sdk.dir=$ANDROID_HOME" > /home/runner/workspace/local.properties
  exit 0
fi

# Optionally skip full install and just fail fast for --check-only callers
if [ "${1:-}" = "--check-only" ]; then
  echo "SDK not found."
  exit 0
fi

echo "▶ Installing Android SDK..."

mkdir -p "$ANDROID_HOME"

# ── 2. Download command-line tools if not cached ─────────────────────────────
if [ ! -f "$CMDLINE_TOOLS_ZIP" ]; then
  echo "  Downloading command-line tools…"
  curl -fsSL "$CMDLINE_TOOLS_URL" -o "$CMDLINE_TOOLS_ZIP"
fi

# Verify zip integrity
if ! unzip -t "$CMDLINE_TOOLS_ZIP" > /dev/null 2>&1; then
  echo "  Corrupt zip — re-downloading…"
  rm -f "$CMDLINE_TOOLS_ZIP"
  curl -fsSL "$CMDLINE_TOOLS_URL" -o "$CMDLINE_TOOLS_ZIP"
  unzip -t "$CMDLINE_TOOLS_ZIP" > /dev/null
fi

# ── 3. Extract command-line tools ─────────────────────────────────────────────
# sdkmanager expects: $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager
mkdir -p "$CMDLINE_TOOLS_DIR/latest"
unzip -q -o "$CMDLINE_TOOLS_ZIP" -d "$CMDLINE_TOOLS_DIR/tmp"
mv "$CMDLINE_TOOLS_DIR/tmp/cmdline-tools/"* "$CMDLINE_TOOLS_DIR/latest/"
rm -rf "$CMDLINE_TOOLS_DIR/tmp"
echo "  ✅ Command-line tools extracted"

SDKMANAGER="$CMDLINE_TOOLS_DIR/latest/bin/sdkmanager"
chmod +x "$SDKMANAGER"

# ── 4. Accept licences ────────────────────────────────────────────────────────
yes | "$SDKMANAGER" --licenses > /dev/null 2>&1 || true

# ── 5. Install required packages ──────────────────────────────────────────────
echo "  Installing SDK packages (platform-34, build-tools 34.0.0, platform-tools)…"
"$SDKMANAGER" \
  "platforms;android-34" \
  "build-tools;34.0.0" \
  "platform-tools"

echo "  ✅ SDK packages installed"

# ── 6. Write local.properties ─────────────────────────────────────────────────
echo "sdk.dir=$ANDROID_HOME" > /home/runner/workspace/local.properties
echo "  ✅ local.properties written"

echo ""
echo "✅ Android SDK setup complete."
