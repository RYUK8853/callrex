#!/bin/bash
# Read or set the audio source (a picker, not a switch).
#   ./audiosrc.sh show          list the options and which is selected
#   ./audiosrc.sh set "<label>" pick one and verify
set -u
cd "$(dirname "$0")" || exit 1
S=${S:-daabf34f}
a() { adb -s "$S" "$@"; }
dump() { a shell uiautomator dump /sdcard/s.xml >/dev/null 2>&1; a shell cat /sdcard/s.xml 2>/dev/null; }
node() { dump | python3 ./uinodes.py "$@"; }
tap_row() { local xy; xy=$(node tap "$1"); [ -z "$xy" ] && return 1; a shell input tap $xy; sleep 2; }

open_audio() {
  a shell am force-stop com.baba.callvault; sleep 1
  a shell monkey -p com.baba.callvault -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 9
  for t in "Not now" "Got it"; do tap_row "$t" >/dev/null 2>&1; done
  a shell input tap 1310 288; sleep 3
  tap_row "AUDIO CONFIGURATION" >/dev/null 2>&1; sleep 2
}

case "${1:-show}" in
  show)
    open_audio
    echo "--- audio section ---"
    dump | python3 ./uinodes.py rows | head -10
    echo "--- opening the source picker ---"
    tap_row "Audio source" >/dev/null 2>&1; sleep 2
    dump | python3 ./uinodes.py rows | head -12
    ;;
  set)
    want="$2"
    open_audio
    tap_row "Audio source" >/dev/null 2>&1; sleep 2
    if tap_row "$want" >/dev/null 2>&1; then
      sleep 2
      for t in OK "Save" "Done"; do tap_row "$t" >/dev/null 2>&1 && break; done
      sleep 2
      echo "selected now: $(dump | python3 ./uinodes.py rows | head -6 | tr '\n' ' ')"
    else
      echo "option '$want' not found; options were:"
      dump | python3 ./uinodes.py rows | head -10
    fi
    ;;
esac
