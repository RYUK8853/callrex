#!/bin/bash
# Reliable Settings driver for the recording matrix.
#
#   ./cvset.sh where <name>            print the screen path a setting lives on
#   ./cvset.sh get <name>              on|off enabled|disabled
#   ./cvset.sh set <name> on|off       set it and VERIFY by reading back
#   ./cvset.sh dump <section> [row]    list the rows on a screen
#   ./cvset.sh report                  read every matrix setting in one pass
#
# Always navigates from a fresh launch and checks it arrived before acting. Half the flakiness today
# was taps landing on a screen that had not finished changing, which silently tested the wrong config.
set -u
cd "$(dirname "$0")" || exit 1
S=${S:-daabf34f}
PKG=com.baba.callvault
a() { adb -s "$S" "$@"; }

dump() {
  a shell uiautomator dump /sdcard/s.xml >/dev/null 2>&1
  a shell cat /sdcard/s.xml 2>/dev/null
}
node() { dump | python3 ./uinodes.py "$@"; }

tap_xy() { a shell input tap "$1" "$2"; }

# Tap a row by label; returns 1 if the label is not on screen.
tap_row() {
  local xy; xy=$(node tap "$1")
  [ -z "$xy" ] && return 1
  tap_xy $xy
  sleep 2
}

# Scroll until the label appears (settings screens are longer than one page).
# Substring, not text="..." — the row reads "Offline recording (no Wi-Fi)" while the setting is known
# here as "Offline recording", so an exact match never scrolled to it and the setting read as missing.
scroll_to() {
  local want="$1" i
  for i in $(seq 1 10); do
    dump | grep -qF "$want" && return 0
    a shell input swipe 540 1900 540 800 250
    sleep 1
  done
  dump | grep -qF "$want"
}

launch_fresh() {
  a shell am force-stop $PKG
  sleep 1
  a shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 9
  for t in OK "Not now" "Got it"; do tap_row "$t" >/dev/null 2>&1; done
}

# Walk to a screen: goto <SECTION> [ROW]
goto() {
  local sec="$1" row="${2:-}" i
  for i in 1 2; do
    launch_fresh
    tap_row "Settings" >/dev/null 2>&1 || a shell input tap 1310 288
    sleep 2
    if ! dump | grep -q 'text="RECORDING"'; then continue; fi
    scroll_to "$sec" >/dev/null 2>&1
    tap_row "$sec" >/dev/null 2>&1 || continue
    sleep 1
    [ -z "$row" ] && return 0
    scroll_to "$row" >/dev/null 2>&1
    tap_row "$row" >/dev/null 2>&1 && sleep 1 && return 0
  done
  echo "!! could not reach $sec${row:+ > $row}" >&2
  return 1
}

# name -> "SECTION|ROW|LABEL"
where() {
  case "$1" in
    resilient)    echo "GENERAL|Experimental|Resilient recording" ;;
    offline)      echo "GENERAL|Experimental|Offline recording" ;;
    usbdebug)     echo "GENERAL|Experimental|USB debugging" ;;
    # Now pinned at the top of Settings, above every section and inside none of them.
    shizuku)      echo "||Shizuku mode" ;;
    voip)         echo "GENERAL|Experimental|Record VoIP calls" ;;
    voipauto)     echo "GENERAL|Experimental|Start automatically" ;;
    carrier)      echo "RECORDING|Phone calls|Record phone calls" ;;
    incoming)     echo "RECORDING|Incoming calls|Automatically record incoming calls" ;;
    outgoing)     echo "RECORDING|Outgoing calls|Automatically record outgoing calls" ;;
    ignoreanon)   echo "RECORDING|Incoming calls|Ignore anonymous incoming calls" ;;
    ignorexcin)   echo "RECORDING|Incoming calls|Ignore cross-country incoming calls" ;;
    ignorexcout)  echo "RECORDING|Outgoing calls|Ignore cross-country outgoing calls" ;;
    *) echo ""; return 1 ;;
  esac
}

cmd_get() {
  local spec sec row lab
  spec=$(where "$1") || { echo "unknown setting: $1"; return 1; }
  sec=${spec%%|*}; row=$(echo "$spec" | cut -d'|' -f2); lab=${spec##*|}
  goto "$sec" "$row" || return 1
  scroll_to "$lab" >/dev/null 2>&1
  node switch "$lab"
}

cmd_set() {
  local name="$1" want="$2" spec sec row lab cur xy
  spec=$(where "$name") || { echo "unknown setting: $name"; return 1; }
  sec=${spec%%|*}; row=$(echo "$spec" | cut -d'|' -f2); lab=${spec##*|}
  goto "$sec" "$row" || return 1
  scroll_to "$lab" >/dev/null 2>&1

  cur=$(node switch "$lab")
  case "$cur" in
    missing|no-switch) echo "$name: cannot read ($cur)"; return 1 ;;
  esac
  case "$cur" in *disabled*) echo "$name: greyed out here ($cur) — not changing"; return 2 ;; esac

  if [ "${cur%% *}" = "$want" ]; then echo "$name: already $want"; return 0; fi

  xy=$(node tapswitch "$lab")
  [ -z "$xy" ] && { echo "$name: no switch to tap"; return 1; }
  tap_xy $xy
  sleep 3
  # Some toggles raise a confirm dialog; accept the affirmative if one appeared.
  # Some toggles raise a confirm dialog first. VoIP's affirmative is "I understand, turn it on" —
  # a generic "OK" never matches it, and the toggle silently stayed off.
  for t in "I understand, turn it on" "Turn on" "Enable" "Continue" "OK"; do
    tap_row "$t" >/dev/null 2>&1 && break
  done
  sleep 2

  local now; now=$(node switch "$lab")
  if [ "${now%% *}" = "$want" ]; then
    echo "$name: $cur -> $now"
  else
    echo "$name: FAILED to set $want (still $now)"
    return 1
  fi
}

# Debug: show every checkable node and the target label, with y coordinates.
cmd_geom() {
  local spec sec row lab
  spec=$(where "$1") || return 1
  sec=${spec%%|*}; row=$(echo "$spec" | cut -d'|' -f2); lab=${spec##*|}
  goto "$sec" "$row" || return 1
  scroll_to "$lab" >/dev/null 2>&1
  dump | python3 ./geom.py "$lab"
}

cmd_dump() {
  goto "$1" "${2:-}" || return 1
  node rows
}

cmd_report() {
  local n
  for n in resilient offline voip carrier incoming outgoing; do
    printf '%-10s %s\n' "$n" "$(cmd_get "$n" 2>/dev/null | tail -1)"
  done
}

case "${1:-report}" in
  where)  where "$2" ;;
  get)    cmd_get "$2" ;;
  set)    cmd_set "$2" "$3" ;;
  dump)   cmd_dump "$2" "${3:-}" ;;
  geom)   cmd_geom "$2" ;;
  report) cmd_report ;;
  *) echo "usage: $0 {where|get|set|dump|report}"; exit 1 ;;
esac
