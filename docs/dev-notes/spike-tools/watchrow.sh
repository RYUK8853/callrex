#!/bin/bash
# Run a matrix row for a call the MAINTAINER places (VoIP — WhatsApp and friends cannot be dialled
# by adb the way a carrier call can).
#
# Waits for the audio mode to enter MODE_IN_COMMUNICATION, then drives the near-side clip exactly as
# the carrier runner does, waits for the call to end, and reports.
#   ./watchrow.sh <row-id> [wait-seconds]
set -u
cd "$(dirname "$0")" || exit 1
S=${S:-daabf34f}
CLIP=/sdcard/Music/nearside.mp3
PLAYER=com.heytap.browser/.core.component.video.StandardVideoActivity
REC_DIR=/sdcard/OP9Recordings
OUT=./pulled
ROW=${1:-S?}
WAIT=${2:-90}
a() { adb -s "$S" "$@"; }

mode()     { a shell dumpsys audio 2>/dev/null | LC_ALL=C grep -m1 'Actual mode' | grep -oE 'MODE_[A-Z_]+'; }
services() { a shell dumpsys activity services com.baba.callvault 2>/dev/null | grep -oE 'ServiceRecord\{[^}]*\}' | sed 's/.*\///' | sort -u | tr '\n' ' '; }
wd()       { a shell settings get global adb_wifi_enabled | tr -d '\r'; }
recs()     { a shell "ls $REC_DIR 2>/dev/null | wc -l" | tr -d '\r'; }

toggle_speaker() {
  a shell uiautomator dump /sdcard/c.xml >/dev/null 2>&1
  local xy
  xy=$(a shell cat /sdcard/c.xml 2>/dev/null | python3 ./uinodes.py tapdesc "speaker")
  if [ -n "$xy" ]; then a shell input tap $xy; sleep 2; echo "   speaker toggled ($1)"
  else echo "   !! no speaker control found ($1) — put the call on speaker by hand"; fi
}

echo "############ ROW $ROW ############"
echo "before: recordings=$(recs) wd=$(wd) mode=$(mode)"

# DO NOT invite a call until the app is actually ready to record one.
#
# VoIP arming is fixed when the capture track is created, so a call arriving before the recorder is up
# is lost for good — there is no retry. Learned the hard way on 2026-08-25: an APK was installed a
# minute earlier (adb install force-stops the app), a real 19-second WhatsApp call ran from 12:53:00 to
# 12:53:19, and CallVault's own process did not start until 12:53:28. Nothing recorded, and nothing
# could even notify, because the app was not there to notice. A void row, not a failed one — and only
# the timestamps tell you which.
echo "== waiting for the recorder to be ready =="
READY=0
for i in $(seq 1 60); do
  HOST=$(a shell ps -A -o PID,ARGS 2>/dev/null | grep -E 'RecorderServer|callvault:recorder' | grep -v ' sh -c ' | head -1)
  APP=$(a shell pidof com.baba.callvault 2>/dev/null | tr -d '\r')
  if [ -n "$HOST" ] && [ -n "$APP" ]; then
    READY=$((READY + 1))
    [ "$READY" -ge 3 ] && { echo "   app pid=$APP and a recorder are both up"; break; }
  else
    READY=0
    printf "."
  fi
  sleep 1
done
if [ "$READY" -lt 3 ]; then
  echo
  echo "   recorder never came up — not inviting a call"
  exit 1
fi

a logcat -c
echo
echo ">>> PLACE THE CALL NOW (waiting up to ${WAIT}s) <<<"
# Require the mode to HOLD, not just appear. A WhatsApp call passes through
# MODE_IN_COMMUNICATION while it is still ringing, and acting on that first sighting meant playing the
# clip into a call that had not connected — and then reading the next dip as the call ending.
STREAK=0
for i in $(seq 1 "$WAIT"); do
  if [ "$(mode)" = "MODE_IN_COMMUNICATION" ]; then
    STREAK=$((STREAK + 1))
    [ "$STREAK" -ge 5 ] && { echo "   call up and holding after ${i}s"; break; }
  else
    STREAK=0
    printf "."
  fi
  sleep 1
done
[ "$STREAK" -lt 5 ] && { echo; echo "   no VoIP call stayed up; giving up"; exit 1; }

# Extra settle so the clip lands in a connected call, not a ringing one.
sleep 4
echo "== mid-call =="
echo "   services: $(services)"
echo "   wd      : $(wd)"

echo "== speakerphone + near-side clip =="
toggle_speaker on
a shell "am start -n $PLAYER -a android.intent.action.VIEW -d file://$CLIP -t audio/mpeg" >/dev/null 2>&1
sleep 6
a shell am force-stop com.heytap.browser >/dev/null 2>&1
toggle_speaker off

echo "   SPEAK NOW, then hang up when you are done"
# Same on the way out: three consecutive readings, so a momentary dip is not mistaken for a hang-up.
GONE=0
for i in $(seq 1 180); do
  if [ "$(mode)" != "MODE_IN_COMMUNICATION" ]; then
    GONE=$((GONE + 1))
    [ "$GONE" -ge 3 ] && { echo "   call ended after ${i}s"; break; }
  else
    GONE=0
  fi
  sleep 1
done
sleep 8

echo
echo "== after =="
echo "   recordings: $(recs)   services: $(services)   wd: $(wd)"
echo
echo "== log =="
a logcat -d -v time 2>/dev/null | LC_ALL=C grep 'CV:' \
  | LC_ALL=C grep -iE 'voip|arm|handoff|direct|scrcpy|speaker turns|Recording (started|stopped)|session|policy' \
  | tail -22 | sed 's/^/   /'

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
  CH=$(ffprobe -v error -show_entries stream=channels -of default=nw=1:nk=1 "$F" | head -1)
  if [ "$CH" = "2" ]; then
    for c in 0 1; do
      printf "   channel %s: " "$c"
      ffmpeg -i "$F" -af "pan=mono|c0=c$c,volumedetect" -f null - 2>&1 | grep -oE 'mean_volume: [-0-9.]+ dB' | head -1
    done
  fi
  echo "   -- silence spans --"
  ffmpeg -i "$F" -af silencedetect=noise=-35dB:d=1.5 -f null - 2>&1 | grep -oE 'silence_(start|end): [0-9.]+' | head -8 | sed 's/^/   /'
}
