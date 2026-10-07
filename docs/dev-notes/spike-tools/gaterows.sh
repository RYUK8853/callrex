#!/bin/bash
# The "gate" rows: turning one thing off must turn off ONLY that thing.
#
# Each row sets a switch, places a short carrier call, and checks whether a recording appeared. No
# audio is needed — the question is whether the app recorded at all, and why it decided that.
set -u
cd "$(dirname "$0")" || exit 1
S=${S:-daabf34f}
NUM=${NUM:-+972507103474}
REC_DIR=/sdcard/OP9Recordings
a() { adb -s "$S" "$@"; }

call_state() { a shell dumpsys telephony.registry 2>/dev/null | grep -m1 -oE 'mCallState=[0-9]' | cut -d= -f2; }
recs()       { a shell "ls $REC_DIR 2>/dev/null | wc -l" | tr -d '\r'; }

# short_call <hold-seconds>
short_call() {
  local hold=${1:-8} i
  a shell "am start -a android.intent.action.CALL -d tel:$NUM" >/dev/null 2>&1
  printf "   ringing "
  for i in $(seq 1 40); do
    [ "$(call_state)" = "2" ] && { echo "-> answered"; break; }
    printf "."
    sleep 1
  done
  if [ "$(call_state)" != "2" ]; then
    echo " -> not answered"
    a shell input keyevent KEYCODE_ENDCALL >/dev/null 2>&1
    return 1
  fi
  sleep "$hold"
  a shell input keyevent KEYCODE_ENDCALL >/dev/null 2>&1
  sleep 6
}

# row <id> <expectation: record|norecord> <description>
row() {
  local id="$1" expect="$2" desc="$3" before after
  echo
  echo "######## $id — $desc (expect: $expect) ########"
  before=$(recs)
  a logcat -c
  short_call 8 || { echo "   SKIPPED (call not answered)"; return; }
  after=$(recs)
  echo "   recordings: $before -> $after"
  if [ "$expect" = "norecord" ]; then
    [ "$after" = "$before" ] && echo "   RESULT: PASS — nothing recorded" || echo "   RESULT: FAIL — it recorded anyway"
  else
    [ "$after" -gt "$before" ] && echo "   RESULT: PASS — recorded" || echo "   RESULT: FAIL — nothing recorded"
  fi
  echo "   why:"
  a logcat -d -v time 2>/dev/null | LC_ALL=C grep 'CV:' \
    | LC_ALL=C grep -iE 'auto-record|ignor|carrier|not record|skip|disabled|startRecording|Sending start' \
    | tail -4 | sed 's/^/      /'
}

echo "=== starting state: $(recs) recordings ==="

# S10 — the carrier master switch off: no phone call should record at all.
bash ./cvset.sh set carrier off >/dev/null 2>&1
echo "carrier -> $(bash ./cvset.sh get carrier 2>/dev/null | tail -1)"
row S10 norecord "carrier recording OFF"
bash ./cvset.sh set carrier on >/dev/null 2>&1
echo "carrier restored -> $(bash ./cvset.sh get carrier 2>/dev/null | tail -1)"

# S12 — auto-record outgoing off: an outgoing call should not record.
bash ./cvset.sh set outgoing off >/dev/null 2>&1
echo "outgoing -> $(bash ./cvset.sh get outgoing 2>/dev/null | tail -1)"
row S12 norecord "auto-record outgoing OFF"
bash ./cvset.sh set outgoing on >/dev/null 2>&1
echo "outgoing restored -> $(bash ./cvset.sh get outgoing 2>/dev/null | tail -1)"

# S7 — VoIP recording off must NOT affect carrier calls (the control row).
bash ./cvset.sh set voip off >/dev/null 2>&1
echo "voip -> $(bash ./cvset.sh get voip 2>/dev/null | tail -1)"
row S7 record "VoIP recording OFF — carrier must be unaffected"
bash ./cvset.sh set voip on >/dev/null 2>&1
echo "voip restored -> $(bash ./cvset.sh get voip 2>/dev/null | tail -1)"

echo
echo "=== final state: $(recs) recordings ==="
