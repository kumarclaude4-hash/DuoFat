#!/bin/bash
# Installs the Android command-line tools + platform 34 + build-tools 34
# into /home/runner/android-sdk and writes local.properties.
# Safe to run multiple times — exits early if already installed.

set -e

SDK_DIR=/home/runner/android-sdk
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"

if [ -d "$SDK_DIR/platforms/android-34" ]; then
  echo "Android SDK already installed at $SDK_DIR — nothing to do."
  # Always keep local.properties up-to-date
  grep -q "^sdk.dir=" local.properties 2>/dev/null || echo "sdk.dir=$SDK_DIR" >> local.properties
  exit 0
fi

echo "==> Downloading Android command-line tools..."
curl -fsSL "$CMDLINE_TOOLS_URL" -o /tmp/cmdline-tools.zip

echo "==> Extracting..."
mkdir -p "$SDK_DIR/cmdline-tools"
unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools-extract
mv /tmp/cmdline-tools-extract/cmdline-tools "$SDK_DIR/cmdline-tools/latest"
rm -f /tmp/cmdline-tools.zip

export ANDROID_HOME=$SDK_DIR
export PATH=$SDK_DIR/cmdline-tools/latest/bin:$PATH

echo "==> Accepting licenses..."
yes | sdkmanager --licenses > /dev/null 2>&1 || true

echo "==> Installing platform-tools, platforms;android-34, build-tools;34.0.0..."
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"

echo "==> Updating local.properties..."
if [ -f local.properties ]; then
  sed -i "s|^sdk.dir=.*|sdk.dir=$SDK_DIR|" local.properties
else
  cat > local.properties << EOF
sdk.dir=$SDK_DIR
supabase.url=
supabase.anon_key=
push.server.url=https://2da12330-d71f-4797-bb75-6efaa5264ac1-00-2t43q8516h13b.pike.replit.dev
EOF
fi

echo "==> Done. SDK installed at $SDK_DIR"
