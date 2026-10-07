#!/bin/bash
# The Shizuku gate rows. Same shape as the standalone ones, plus a check that the switch itself still
# lands on the right host — the thing that broke twice today.
set -u
cd "$(dirname "$0")" || exit 1
S=${S:-daabf34f}
NUM=${NUM:-+972507103474}
REC_DIR=/sdcard/OP9Recordings
a() { adb -s "$S" "$@"; }

call_state() { a shell dumpsys telephony.registry 2>/dev/null | grep -m1 -oE 'mCallState=[0-9]' | cut -d= -f2; }
recs()       { a shell "ls $REC_DIR 2>/dev/null | wc -l" | tr -d '\r'; }
host()       { a shell ps -A -o PID,ARGS 2>/dev/null | grep -E 'RecorderServer|callvault:recorder' | grep -v ' sh -c ' \
                 | sed -E 's/^ *([0-9]+).*:recorder.*/shizuku(\1)/; s/^ *([0-9]+).*RecorderServer.*/daemon(\1)/' | tr '\n' ' '; }

short_call() {
  local hold=${1:-8} i
  a shell "am start -a android.intent.action.CALL -d tel:$NUM" >/dev/null 2>&1
  printf "   ringing "
  for i in $(seq 1 40); do
    [ "$(call_state)" = "2" ] && { echo "-> answered"; break; }
    printf "."
    sleep 1
  done
  [ "$(call_state)" != "2" ] && { echo " -> not answered"; a shell input keyevent KEYCODE_ENDCALL >/dev/null 2>&1; return 1; }
  sleep "$hold"
  a shell input keyevent KEYCODE_ENDCALL >/dev/null 2>&1
  sleep 6
}

row() {
  local id="$1" expect="$2" desc="$3" before after
  echo
  echo "######## $id — $desc (expect: $expect) ########"
  before=$(recs)
  a logcat -c
  short_call 8 || { echo "   SKIPPED"; return; }
  after=$(recs)
  echo "   recordings: $before -> $after   host: $(host)"
  if [ "$expect" = "norecord" ]; then
    [ "$after" = "$before" ] && echo "   RESULT: PASS — nothing recorded" || echo "   RESULT: FAIL — it recorded anyway"
  else
    [ "$after" -gt "$before" ] && echo "   RESULT: PASS — recorded" || echo "   RESULT: FAIL — nothing recorded"
  fi
  echo "   why:"
  a logcat -d -v time 2>/dev/null | LC_ALL=C grep 'CV:' \
    | LC_ALL=C grep -iE 'auto-record|ignor|carrier|not record|scrcpy|startRecording|Sending start' \
    | tail -4 | sed 's/^/      /'
}

echo "=== switching to Shizuku ==="
bash ./cvset.sh set shizuku on >/dev/null 2>&1
sleep 4
echo "host after the switch: $(host)"
echo "VoIP switch in this mode: $(bash ./cvset.sh get voip 2>/dev/null | tail -1)   <- expect 'off DISABLED'"

# Z3 — the carrier master switch, under Shizuku.
bash ./cvset.sh set carrier off >/dev/null 2>&1
row Z3 norecord "carrier recording OFF, Shizuku"
bash ./cvset.sh set carrier on >/dev/null 2>&1

# Z4 — auto-record outgoing, under Shizuku.
bash ./cvset.sh set outgoing off >/dev/null 2>&1
row Z4 norecord "auto-record outgoing OFF, Shizuku"
bash ./cvset.sh set outgoing on >/dev/null 2>&1

echo
echo "=== back to standalone ==="
bash ./cvset.sh set shizuku off >/dev/null 2>&1
sleep 4
echo "host: $(host)"
a logcat -d -v time 2>/dev/null | LC_ALL=C grep 'CV:' | LC_ALL=C grep -iE 'mode is now|Turned back on' | tail -3 | sed 's/^/   /'
