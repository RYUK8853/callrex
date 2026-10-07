#!/bin/bash
# Two-sided call harness for the OP9.
#
#   ./callharness.sh state              what mode / recorder / call state is the phone in
#   ./callharness.sh call [seconds]     place a call to פרוזה, play the near-side clip, hang up
#                                       (default 20s: ~5s clip, then room for a short reply)
#   ./callharness.sh collect            pull the newest recording and measure it
#   ./callharness.sh clip               (re)push the near-side clip and set media volume
#
# The near side is a short spoken clip ("Testing, testing. One. Two. Three. This is OP9.") played
# out of the OP9's own speaker so its microphone transmits it. The far side is a human on the OP12. Both are then
# identifiable in one file — by ear, and in the transcript CallVault produces itself.
#
# Generate the clip on a Mac with:
#   say -v Daniel -r 175 -o clip.aiff "Testing, testing. One. Two. Three. This is O P nine."
#   ffmpeg -i clip.aiff -ac 1 -ar 44100 -b:a 128k nearside.mp3
#
# Keep it SHORT — about 5 s. The clip only has to prove the near side reached the file; a long one
# just makes every test call take a minute.
#
# Reading the numbers:
#   ~-34 dB mean   a real two-sided carrier call (measured, OP9, 2026-08-24)
#   ~-73 dB mean   digital silence — the file exists, the pipeline reported success, nothing was
#                  captured. This is the failure shape that matters, and it is invisible in logs.
#
# Carrier recordings are MONO, so the per-channel check below only applies to VoIP (stereo). For a
# carrier call, "not silent" is all the numbers can prove: that BOTH sides are present is settled by
# the transcript (English counting AND Hebrew) or by listening.
set -u
cd "$(dirname "$0")" || exit 1

S=${S:-daabf34f}
NUM=${NUM:-+972507103474}          # פרוזה (the OP12)
CLIP=/sdcard/Music/nearside.mp3
PLAYER=com.heytap.browser/.core.component.video.StandardVideoActivity
REC_DIR=/sdcard/OP9Recordings
OUT=/private/tmp/claude-501/-Users-kfirbaba-Desktop-Projects-callrecorder/a28a3105-2a12-463e-badb-0e221b3ca7c5/scratchpad/pulled

a() { adb -s "$S" "$@"; }

call_state() {
  # 0 = idle, 1 = ringing, 2 = off-hook (in a call)
  a shell dumpsys telephony.registry 2>/dev/null | grep -m1 -oE 'mCallState=[0-9]' | cut -d= -f2
}

recorders_desc() {
  a shell ps -A -o PID,ARGS 2>/dev/null \
    | grep -E 'com\.baba\.callvault\.server\.RecorderServer|com\.baba\.callvault:recorder' \
    | grep -v ' sh -c ' \
    | sed -E 's/^ *([0-9]+).*:recorder.*/shizuku(\1)/; s/^ *([0-9]+).*RecorderServer.*/daemon(\1)/' | tr '\n' ' '
}

cmd_state() {
  echo "call state    : $(call_state)  (0=idle 1=ringing 2=in-call)"
  echo "recorder      : $(recorders_desc)"
  echo "adb_wifi      : $(a shell settings get global adb_wifi_enabled | tr -d '\r')"
  echo "media vol(spk): $(a shell dumpsys audio 2>/dev/null | grep -A8 'STREAM_MUSIC:' | grep -m1 -oE '2 \(speaker\): [0-9]+')"
  echo "clip on phone : $(a shell "ls -la $CLIP 2>/dev/null | wc -l" | tr -d '\r') (1 = present)"
  echo "recordings    : $(a shell "ls $REC_DIR 2>/dev/null | wc -l" | tr -d '\r')"
}

cmd_clip() {
  a push ./nearside.mp3 "$CLIP" >/dev/null 2>&1 && echo "clip pushed"
  # Media volume on the speaker was 0 out of the box — the clip played, silently.
  a shell service call audio 12 i32 3 i32 26 i32 0 >/dev/null 2>&1
  echo "media volume (speaker) set to 26/30"
}

play_clip() { a shell "am start -n $PLAYER -a android.intent.action.VIEW -d file://$CLIP -t audio/mpeg" >/dev/null 2>&1; }
stop_clip() { a shell am force-stop com.heytap.browser >/dev/null 2>&1; }

cmd_call() {
  local secs=${1:-20}
  echo "== placing a call to $NUM =="
  a logcat -c
  a shell "am start -a android.intent.action.CALL -d tel:$NUM" >/dev/null 2>&1

  echo -n "   waiting for answer "
  local i
  for i in $(seq 1 40); do
    [ "$(call_state)" = "2" ] && { echo " -> answered"; break; }
    echo -n "."
    sleep 1
  done
  if [ "$(call_state)" != "2" ]; then
    echo " -> never went off-hook; hanging up"
    a shell input keyevent KEYCODE_ENDCALL >/dev/null 2>&1
    return 1
  fi

  sleep 3
  echo "== near-side clip starting =="
  play_clip

  echo "   in call for ${secs}s — the clip runs ~5s, then speak on the OP12 and I will hang up"
  for i in $(seq 1 "$secs"); do
    if [ "$(call_state)" != "2" ]; then echo "   call ended early at ${i}s"; break; fi
    sleep 1
  done

  stop_clip
  echo "== hanging up =="
  a shell input keyevent KEYCODE_ENDCALL >/dev/null 2>&1
  sleep 6
  echo "   call state now: $(call_state)"
}

cmd_collect() {
  mkdir -p "$OUT"
  local newest
  newest=$(a shell "ls -t $REC_DIR 2>/dev/null | head -1" | tr -d '\r')
  [ -z "$newest" ] && { echo "no recordings found in $REC_DIR"; return 1; }
  echo "newest: $newest"
  a pull "$REC_DIR/$newest" "$OUT/" >/dev/null 2>&1 || { echo "pull failed"; return 1; }
  local f="$OUT/$newest"
  echo "pulled: $f"
  echo "size  : $(stat -f%z "$f" 2>/dev/null) bytes"
  echo "--- ffprobe ---"
  ffprobe -v error -show_entries format=duration:stream=channels,sample_rate,codec_name -of default=nw=1 "$f"
  # NOTE: no -v error here. volumedetect reports at info level, so quieting ffmpeg hides the very
  # number we are asking for — which is how a -73 dB (digitally silent) file could look like a pass.
  echo "--- level (silence would mean nothing was captured) ---"
  ffmpeg -i "$f" -af volumedetect -f null - 2>&1 | grep -E 'mean_volume|max_volume'

  local ch
  ch=$(ffprobe -v error -show_entries stream=channels -of default=nw=1:nk=1 "$f" | head -1)
  if [ "$ch" = "2" ]; then
    echo "--- per channel (a two-sided call must have BOTH above the noise floor) ---"
    # One pass per channel: a single filter_complex with two null outputs silently produced nothing.
    local c
    for c in 0 1; do
      echo -n "  channel $c: "
      ffmpeg -i "$f" -af "pan=mono|c0=c$c,volumedetect" -f null - 2>&1 \
        | grep -oE 'mean_volume: [-0-9.]+ dB|max_volume: [-0-9.]+ dB' | tr '\n' '  '
      echo
    done
  fi

  echo "--- talk segments (gaps of >1.5s below -35dB) ---"
  ffmpeg -i "$f" -af silencedetect=noise=-35dB:d=1.5 -f null - 2>&1 \
    | grep -oE 'silence_(start|end): [0-9.]+' | head -12
}

case "${1:-state}" in
  state)   cmd_state ;;
  clip)    cmd_clip ;;
  call)    cmd_call "${2:-45}" ;;
  collect) cmd_collect ;;
  *) echo "usage: $0 {state|clip|call [seconds]|collect}"; exit 1 ;;
esac
