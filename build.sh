#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/android"

./gradlew assembleDebug

printf '\nAPK nativo: %s\n' "$SCRIPT_DIR/android/app/build/outputs/apk/debug/app-debug.apk"
