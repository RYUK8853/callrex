#!/bin/bash
# Run one matrix row end to end: place the call, observe the services WHILE it is up, hang up,
# then report the capture path, the WD status and the audio.
#   ./runrow.sh <row-id> [seconds]
set -u
cd "$(dirname "$0")" || exit 1
S=${S:-daabf34f}
NUM=${NUM:-+972507103474}
CLIP=/sdcard/Music/nearside.mp3
PLAYER=com.heytap.browser/.core.component.video.StandardVideoActivity
REC_DIR=/sdcard/OP9Recordings
OUT=./pulled
ROW=${1:-S?}
SECS=${2:-20}
a() { adb -s "$S" "$@"; }

call_state() { a shell dumpsys telephony.registry 2>/dev/null | grep -m1 -oE 'mCallState=[0-9]' | cut -d= -f2; }
services()   { a shell dumpsys activity services com.baba.callvault 2>/dev/null | grep -oE 'ServiceRecord\{[^}]*\}' | sed 's/.*\///' | sort -u | tr '\n' ' '; }
wd()         { a shell settings get global adb_wifi_enabled | tr -d '\r'; }
recs()       { a shell "ls $REC_DIR 2>/dev/null | wc -l" | tr -d '\r'; }

echo "############ ROW $ROW ############"
echo "before: recordings=$(recs) wd=$(wd) services=$(services)"
a logcat -c

echo "== dialling $NUM =="
a shell "am start -a android.intent.action.CALL -d tel:$NUM" >/dev/null 2>&1
printf "   waiting for answer "
for i in $(seq 1 45); do
  [ "$(call_state)" = "2" ] && { echo " -> ANSWERED"; break; }
  printf "."
  sleep 1
done
if [ "$(call_state)" != "2" ]; then
  echo " -> never answered; hanging up"
  a shell input keyevent KEYCODE_ENDCALL >/dev/null 2>&1
  exit 1
fi

sleep 2

# Speakerphone FIRST. During a call, media audio goes to the earpiece, so the near-side clip played
# perfectly and the microphone never heard a thing — the first S1 run recorded 11 s of silence exactly
# where the clip should have been. On speaker, both the call and the clip come out of the loudspeaker,
# which the microphone can hear.
toggle_speaker() {
  a shell uiautomator dump /sdcard/c.xml >/dev/null 2>&1
  local xy
  xy=$(a shell cat /sdcard/c.xml 2>/dev/null | python3 ./uinodes.py tapdesc "speaker")
  if [ -n "$xy" ]; then
    a shell input tap $xy; sleep 2
    echo "   speaker toggled ($1)"
  else
    echo "   !! no speaker control found ($1); in-call controls were:"
    a shell cat /sdcard/c.xml 2>/dev/null | python3 ./uinodes.py descs | head -8 | sed 's/^/      /'
  fi
}

echo "== speakerphone =="
toggle_speaker on
echo "   audio route: $(a shell dumpsys audio 2>/dev/null | LC_ALL=C grep -m1 -iE 'Device: |mainType|routes' | tr -d '\r')"

echo "== mid-call =="
echo "   services: $(services)"
echo "   wd      : $(wd)"

echo "== near-side clip =="
a shell "am start -n $PLAYER -a android.intent.action.VIEW -d file://$CLIP -t audio/mpeg" >/dev/null 2>&1

# Off speaker again as soon as the clip has played. Left on, the loudspeaker replays the far side
# straight back into this phone's own microphone, so the far voice lands on both sides of the
# recording and the near side is no longer a clean signal to judge.
CLIP_SECS=6
sleep $CLIP_SECS
a shell am force-stop com.heytap.browser >/dev/null 2>&1
toggle_speaker off
SECS=$(( SECS > CLIP_SECS ? SECS - CLIP_SECS : 4 ))

echo "   holding ${SECS}s — SPEAK NOW on the other phone"
for i in $(seq 1 "$SECS"); do
  [ "$(call_state)" != "2" ] && { echo "   ended early at ${i}s"; break; }
  sleep 1
done

a shell am force-stop com.heytap.browser >/dev/null 2>&1
echo "== hanging up =="
a shell input keyevent KEYCODE_ENDCALL >/dev/null 2>&1
sleep 8

echo
echo "== after =="
echo "   recordings: $(recs)   services: $(services)   wd: $(wd)"

echo
echo "== capture path, from the app's log =="
a logcat -d -v time 2>/dev/null | LC_ALL=C grep 'CV:' \
  | LC_ALL=C grep -iE 'handoff|direct|scrcpy|armVoip|speaker turns|startRecording|Recording (started|stopped)|wireless debugging|session' \
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
  # Threshold RELATIVE to this file's own level, not a fixed -35 dB.
  #
  # The handoff path averages about -35 dB, so a fixed -35 dB threshold marks ordinary speech as
  # silence: Z8 reported twelve seconds of "silence" in a recording the maintainer then confirmed had
  # both sides perfectly audible. A false "the near side is missing" is worse than no check at all —
  # it sends you hunting a capture bug that is not there.
  MEAN=$(ffmpeg -i "$F" -af volumedetect -f null - 2>&1 | grep -oE 'mean_volume: [-0-9.]+' | grep -oE '[-0-9.]+$')
  THRESH=$(python3 -c "print(round(min(float('$MEAN') - 12, -45), 1))" 2>/dev/null || echo -45)
  echo "   -- quiet spans (below ${THRESH}dB, i.e. 12 dB under this file's own mean) --"
  ffmpeg -i "$F" -af "silencedetect=noise=${THRESH}dB:d=1.5" -f null - 2>&1 | grep -oE 'silence_(start|end): [0-9.]+' | head -8 | sed 's/^/   /'
}
