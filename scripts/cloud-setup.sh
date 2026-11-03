#!/usr/bin/env bash
# Prepares a fresh Linux machine (a Claude Code cloud session or a CI box) to build Zella for
# Android: Java 17 and the Android SDK pieces the build needs. Safe to run more than once.
# Run by .claude/settings.json at session start, only when CLAUDE_CODE_REMOTE is "true".
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# whisper.cpp is a submodule (third_party/whisper.cpp, v1.9.5).
git -C "$ROOT" submodule update --init --depth 1 || true
SDK="${ANDROID_HOME:-$HOME/android-sdk}"

# Java 17: the Gradle wrapper and AGP 8.11 need it.
if ! java -version 2>&1 | grep -q 'version "17'; then
  if command -v apt-get >/dev/null; then
    (sudo -n true 2>/dev/null && SUDO=sudo) || SUDO=""
    $SUDO apt-get update -qq && $SUDO apt-get install -y -qq openjdk-17-jdk-headless >/dev/null
  fi
fi
J17=$(ls -d /usr/lib/jvm/*17* 2>/dev/null | head -1 || true)
[ -n "$J17" ] && export JAVA_HOME="$J17"

# Android SDK: command-line tools, then the platform and build tools the project compiles against.
if [ ! -d "$SDK/platforms/android-36" ] || [ ! -d "$SDK/ndk/27.0.12077973" ]; then
  mkdir -p "$SDK/cmdline-tools"
  if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
    curl -fsSL -o /tmp/cmdline.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
    unzip -q -o /tmp/cmdline.zip -d "$SDK/cmdline-tools"
    mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
  fi
  yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null || true
  "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" "platforms;android-36" "build-tools;36.0.0" "platform-tools" "ndk;27.0.12077973" "cmake;3.22.1" >/dev/null
fi
echo "sdk.dir=$SDK" > "$ROOT/local.properties"

# Make the variables visible to the rest of the session.
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  { [ -n "$J17" ] && echo "export JAVA_HOME=$J17"; echo "export ANDROID_HOME=$SDK"; } >> "$CLAUDE_ENV_FILE"
fi
echo "Android build ready: JAVA_HOME=${JAVA_HOME:-unset} ANDROID_HOME=$SDK"
