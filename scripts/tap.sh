#!/usr/bin/env bash
# Tap the centre of the first UI node whose text or content-desc equals the argument
# (uiautomator dump). Used for autonomous checks on the tablet:
#   scripts/tap.sh "Старт"
# Device: CUPOLA_DEVICE (default 192.168.0.16:5555).
set -euo pipefail
export ANDROID_SERIAL="${CUPOLA_DEVICE:-192.168.0.16:5555}"
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
adb shell cat /sdcard/ui.xml > /tmp/cupola-ui.xml
python3 - "$1" <<'PY'
import re, subprocess, sys
t = sys.argv[1]
s = open('/tmp/cupola-ui.xml', encoding='utf-8').read()
m = re.search(r'<node[^>]*(?:text|content-desc)="%s"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % re.escape(t), s)
if not m:
    print("NOT FOUND:", t)
    sys.exit(1)
x = (int(m.group(1)) + int(m.group(3))) // 2
y = (int(m.group(2)) + int(m.group(4))) // 2
print("tap", t, x, y)
subprocess.run(["adb", "shell", "input", "tap", str(x), str(y)], check=True)
PY
