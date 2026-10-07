#!/bin/bash
# Map the whole Settings tree in ONE pass: launch once, then walk with Back.
#
# The earlier mapper force-stopped and relaunched the app for every single lookup — ~10 s each, and
# every relaunch made the app reconnect over ADB, which flips Wireless debugging on and (since the
# lease fix) straight back off. Dozens of visible WD blips for no reason. This stays inside Settings.
set -u
cd "$(dirname "$0")" || exit 1
S=${S:-daabf34f}
a() { adb -s "$S" "$@"; }
dump() { a shell uiautomator dump /sdcard/s.xml >/dev/null 2>&1; a shell cat /sdcard/s.xml 2>/dev/null; }
node() { dump | python3 ./uinodes.py "$@"; }
tap_row() { local xy; xy=$(node tap "$1"); [ -z "$xy" ] && return 1; a shell input tap $xy; sleep 2; }
back() { a shell input keyevent KEYCODE_BACK; sleep 2; }

# Back can land outside Settings entirely; re-enter rather than silently mapping nothing.
ensure_settings() {
  local i
  for i in 1 2 3; do
    dump | grep -q 'text="RECORDING"' && return 0
    a shell input tap 1310 288; sleep 3
    dump | grep -q 'text="RECORDING"' && return 0
    back
  done
  return 1
}

# Read a whole screen, scrolling, collecting labels and switch states.
read_screen() {
  local seen_rows="" i
  for i in 0 1 2 3; do
    [ $i -gt 0 ] && { a shell input swipe 540 1900 540 800 250; sleep 1; }
    dump | python3 ./uinodes.py pairs
  done | sort -u -k3
}

echo "############ launching once ############"
a shell am force-stop com.baba.callvault; sleep 1
a shell monkey -p com.baba.callvault -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 10
for t in OK "Not now" "Got it"; do tap_row "$t" >/dev/null 2>&1; done
a shell input tap 1310 288; sleep 3
dump | grep -q 'text="RECORDING"' || { echo "!! not on Settings"; exit 1; }
echo "on Settings"

for sec in RECORDING STORAGE "AUDIO CONFIGURATION" GENERAL; do
  echo
  echo "######## $sec ########"
  ensure_settings || { echo "  (lost Settings)"; continue; }
  tap_row "$sec" >/dev/null 2>&1 || { echo "  (could not open)"; continue; }
  echo "--- rows ---"
  dump | python3 ./uinodes.py rows | head -18
  echo "--- switches ---"
  read_screen
  # Descend into any sub-screens this section has.
  for sub in "Phone calls" "Incoming calls" "Outgoing calls" "Experimental" "Visual settings" "Updates"; do
    if dump | grep -qF "text=\"$sub\""; then
      tap_row "$sub" >/dev/null 2>&1 || continue
      echo "--- $sec > $sub ---"
      read_screen
      back
      ensure_settings >/dev/null 2>&1
      tap_row "$sec" >/dev/null 2>&1
    fi
  done
  back
done
