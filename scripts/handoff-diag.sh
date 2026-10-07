#!/usr/bin/env bash
# Handoff diagnostics — run right after connecting the phone to establish WHY a recording failed.
# Distinguishes the two candidate causes without needing a live call.
#   A) leaked VOICE_CALL capture input (a handed-off track never released) -> next recording is empty
#   B) broken ADB transport / daemon unreachable -> startPipeline hangs, recording never starts
# Usage: scripts/handoff-diag.sh [serial]
set -uo pipefail
D="${1:-6011b07e}"
A() { adb -s "$D" "$@"; }

echo "=================== CallVault handoff diagnostics ==================="
echo "--- device / build ---"
A shell "dumpsys package com.baba.callvault | grep -m1 -E 'versionName|versionCode'"
A shell "getprop sys.usb.config; getprop service.adb.tcp.port; settings get global adb_wifi_enabled"

echo
echo "--- (B) daemon + app processes ---"
A shell 'ps -A -o PID,USER,NAME | awk "\$2==\"shell\" && \$3==\"app_process\"{print \"daemon pid=\" \$1}"' || echo "daemon=DEAD"
A shell 'echo "app pid=$(pidof com.baba.callvault)"'

echo
echo "--- (A) LEAKED CAPTURE: any active record client? (uid 10xxx=app, 2000=daemon) ---"
echo "    Expect NO active VOICE_CALL client when idle. An active one = leaked handoff track."
A shell "dumpsys audio 2>/dev/null | grep -E 'rec (start|update)' | tail -8"
echo "    -- audioflinger record tracks --"
A shell "dumpsys media.audio_flinger 2>/dev/null | grep -A3 -iE 'Input thread' | grep -iE 'Tracks of which|active' | head -6"

echo
echo "--- (A) app threads: cv-handoff-* must NOT be present when idle ---"
A shell 'P=$(pidof com.baba.callvault); for t in /proc/$P/task/*/comm; do C=$(cat $t 2>/dev/null); case "$C" in cv-*) echo "  $C";; esac; done; echo "  (nothing above = clean)"'

echo
echo "--- recent recordings (0 bytes = failed) ---"
A shell "ls -la /sdcard/CallRecording/ 2>/dev/null | tail -6"
echo "===================================================================="
echo "READ: daemon DEAD or app can't reach it  -> cause (B) transport; relaunch daemon."
echo "      active record client / cv-handoff-* while idle -> cause (A) leaked track."
