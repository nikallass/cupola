#!/usr/bin/env bash
# Build Cupola on the x86_64 build server and install it on the development tablet.
#
#   scripts/deploy.sh              rsync → server → :app:assembleDebug → scp APK → adb install → launch
#   scripts/deploy.sh --test       only run ./gradlew :core-dsp:test (and other JVM tests) on the server
#   scripts/deploy.sh --check      run ./gradlew check (tests + no-android-imports guard)
#   scripts/deploy.sh --no-install build and download the APK, do not install
#   scripts/deploy.sh --logcat     after launching, tail logcat of the app process (Ctrl+C to stop)
#   scripts/deploy.sh -- <args>    pass extra Gradle arguments, e.g. -- --tests "*.PitchTest"
#
# Environment overrides: CUPOLA_SERVER, CUPOLA_SSH_KEY, CUPOLA_REMOTE_DIR, CUPOLA_DEVICE.

set -euo pipefail

SERVER="${CUPOLA_SERVER:-root@217.60.62.102}"
SSH_KEY="${CUPOLA_SSH_KEY:-$HOME/.ssh/llms_id_rsa}"
REMOTE_DIR="${CUPOLA_REMOTE_DIR:-/root/cupola}"
DEVICE="${CUPOLA_DEVICE:-192.168.0.16:5555}"
PACKAGE="ru.dvedev.me.cupola"
ACTIVITY="$PACKAGE/.MainActivity"
ANDROID_HOME_REMOTE="/opt/android-sdk"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCAL_APK_DIR="$ROOT/build"
LOCAL_APK="$LOCAL_APK_DIR/cupola-debug.apk"
REMOTE_APK="$REMOTE_DIR/app/build/outputs/apk/debug/app-debug.apk"

MODE="build"
INSTALL=1
LOGCAT=0
EXTRA_ARGS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --test) MODE="test" ;;
    --check) MODE="check" ;;
    --no-install) INSTALL=0 ;;
    --logcat) LOGCAT=1 ;;
    --) shift; EXTRA_ARGS=("$@"); break ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

SSH=(ssh -i "$SSH_KEY" -o ConnectTimeout=15 -o BatchMode=yes "$SERVER")
started=$(date +%s)
step() { printf '\n\033[1;33m▶ %s\033[0m\n' "$*"; }
elapsed() { echo "$(( $(date +%s) - started )) s"; }

step "rsync → $SERVER:$REMOTE_DIR"
"${SSH[@]}" "mkdir -p '$REMOTE_DIR'"
rsync -az --delete \
  --exclude '.git/' --exclude '.gradle/' --exclude '.kotlin/' --exclude '.idea/' \
  --exclude 'build/' --exclude '**/build/' --exclude 'local.properties' --exclude '*.apk' \
  -e "ssh -i $SSH_KEY -o BatchMode=yes" \
  "$ROOT/" "$SERVER:$REMOTE_DIR/"

case "$MODE" in
  test)  GRADLE_TASKS=":core-dsp:test :core-notation:test" ;;
  check) GRADLE_TASKS="check" ;;
  build) GRADLE_TASKS=":app:assembleDebug" ;;
esac

GRADLE_EXTRA=""
if [[ ${#EXTRA_ARGS[@]} -gt 0 ]]; then
  GRADLE_EXTRA=$(printf '%q ' "${EXTRA_ARGS[@]}")
fi

step "gradle $GRADLE_TASKS $GRADLE_EXTRA"
"${SSH[@]}" "cd '$REMOTE_DIR' && export ANDROID_HOME='$ANDROID_HOME_REMOTE' && chmod +x gradlew && ./gradlew --console=plain -q $GRADLE_TASKS $GRADLE_EXTRA"

if [[ "$MODE" != "build" ]]; then
  step "done in $(elapsed)"
  exit 0
fi

step "scp APK → $LOCAL_APK"
mkdir -p "$LOCAL_APK_DIR"
scp -q -i "$SSH_KEY" -o BatchMode=yes "$SERVER:$REMOTE_APK" "$LOCAL_APK"
ls -la "$LOCAL_APK"

if [[ $INSTALL -eq 0 ]]; then
  step "built in $(elapsed) (not installed)"
  exit 0
fi

step "adb install → $DEVICE"
adb connect "$DEVICE" >/dev/null || true
state=$(adb -s "$DEVICE" get-state 2>/dev/null || echo "absent")
if [[ "$state" != "device" ]]; then
  echo "device $DEVICE is '$state' — re-run 'adb tcpip 5555' over USB on the tablet" >&2
  exit 3
fi
adb -s "$DEVICE" install -r -t "$LOCAL_APK"

step "launch $ACTIVITY"
adb -s "$DEVICE" shell am force-stop "$PACKAGE" || true
adb -s "$DEVICE" shell am start -W -n "$ACTIVITY" | sed -n '/TotalTime/p'

step "deployed in $(elapsed)"

if [[ $LOGCAT -eq 1 ]]; then
  pid=""
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    pid=$(adb -s "$DEVICE" shell pidof -s "$PACKAGE" 2>/dev/null | tr -d '\r' || true)
    [[ -n "$pid" ]] && break
    sleep 0.5
  done
  if [[ -z "$pid" ]]; then
    echo "process $PACKAGE not found" >&2
    exit 4
  fi
  step "logcat pid=$pid (Ctrl+C to stop)"
  adb -s "$DEVICE" logcat --pid="$pid" -v time
fi
