#!/bin/bash
# The incoming-call row. Every test call so far has been OUTGOING, and the incoming path is not a
# variation on it: CallSessionManager handles direction separately, PhoneStateReceiver sees a different
# state sequence, auto-record incoming is its own switch, and contact resolution runs against a number
# this phone did not dial.
#
# Answers the call itself so nothing has to be touched on the OP9.
set -u
cd "$(dirname "$0")" || exit 1
S=${S:-daabf34f}
CLIP=/sdcard/Music/nearside.mp3
PLAYER=com.heytap.browser/.core.component.video.StandardVideoActivity
REC_DIR=/sdcard/OP9Recordings
OUT=./pulled
HOLD=${1:-20}
a() { adb -s "$S" "$@"; }

call_state() { a shell dumpsys telephony.registry 2>/dev/null | grep -m1 -oE 'mCallState=[0-9]' | cut -d= -f2; }
services()   { a shell dumpsys activity services com.baba.callvault 2>/dev/null | grep -oE 'ServiceRecord\{[^}]*\}' | sed 's/.*\///' | sort -u | tr '\n' ' '; }
wd()         { a shell settings get global adb_wifi_enabled | tr -d '\r'; }
recs()       { a shell "ls $REC_DIR 2>/dev/null | wc -l" | tr -d '\r'; }

toggle_speaker() {
  a shell uiautomator dump /sdcard/c.xml >/dev/null 2>&1
  local xy
  xy=$(a shell cat /sdcard/c.xml 2>/dev/null | python3 ./uinodes.py tapdesc "speaker")
  [ -n "$xy" ] && { a shell input tap $xy; sleep 2; echo "   speaker toggled ($1)"; } \
                || echo "   !! no speaker control found ($1)"
}

echo "############ INCOMING ############"
BEFORE=$(recs)
echo "before: recordings=$BEFORE wd=$(wd)"
a logcat -c

WAIT=${WAIT:-300}
echo ">>> CALL THE OP9 FROM THE OP12 NOW (waiting ${WAIT}s) <<<"
for i in $(seq 1 "$WAIT"); do
  ST=$(call_state)
  [ "$ST" = "1" ] && { echo "   ringing after ${i}s"; break; }
  [ "$ST" = "2" ] && { echo "   already off-hook after ${i}s"; break; }
  printf "."
  sleep 1
done
[ "$(call_state)" = "0" ] && { echo; echo "   no incoming call arrived"; exit 1; }

# Answer it ourselves.
if [ "$(call_state)" = "1" ]; then
  echo "== answering =="
  a shell input keyevent KEYCODE_HEADSETHOOK >/dev/null 2>&1
  sleep 2
  if [ "$(call_state)" != "2" ]; then
    a shell input keyevent KEYCODE_CALL >/dev/null 2>&1
    sleep 2
  fi
fi
[ "$(call_state)" != "2" ] && { echo "   could not answer — answer on the OP9"; sleep 8; }

echo "   call state: $(call_state)"
sleep 3
echo "== mid-call =="
echo "   services: $(services)"
echo "   wd      : $(wd)"

toggle_speaker on
a shell "am start -n $PLAYER -a android.intent.action.VIEW -d file://$CLIP -t audio/mpeg" >/dev/null 2>&1
sleep 6
a shell am force-stop com.heytap.browser >/dev/null 2>&1
toggle_speaker off

echo "   SPEAK NOW on the OP12"
for i in $(seq 1 "$HOLD"); do
  [ "$(call_state)" != "2" ] && { echo "   ended early at ${i}s"; break; }
  sleep 1
done
a shell input keyevent KEYCODE_ENDCALL >/dev/null 2>&1
sleep 8

echo
echo "== after =="
echo "   recordings: $BEFORE -> $(recs)   wd: $(wd)"
echo
echo "== log =="
a logcat -d -v time 2>/dev/null | LC_ALL=C grep 'CV:' \
  | LC_ALL=C grep -iE 'incoming|handoff|direct|scrcpy|speaker turns|auto-record|contact|Sending start|RINGING' \
  | tail -18 | sed 's/^/   /'

echo
echo "== the file =="
mkdir -p "$OUT"
NEW=$(a shell "ls -t $REC_DIR 2>/dev/null | head -1" | tr -d '\r')
echo "   newest: $NEW"
a pull "$REC_DIR/$NEW" "$OUT/" >/dev/null 2>&1 && {
  F="$OUT/$NEW"
  echo "   size: $(stat -f%z "$F") bytes"
  ffprobe -v error -show_entries format=duration:stream=channels,codec_name -of default=nw=1 "$F" | sed 's/^/   /'
  ffmpeg -i "$F" -af volumedetect -f null - 2>&1 | grep -oE 'mean_volume: [-0-9.]+ dB|max_volume: [-0-9.]+ dB' | sed 's/^/   /'
}
