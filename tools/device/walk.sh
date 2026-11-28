#!/bin/bash
# Device check (8.54): walks the app on an emulator like a person would and keeps what it saw.
#
#   walk.sh tv|phone <apk> <out dir>
#
# Each step leaves NN-name.png (the screen), and the scrolling steps gfx-name.txt (frame timings from
# `dumpsys gfxinfo`). At the end: logcat.txt, crash.txt (Android's crash log) and summary.txt.
# The test playlist comes from tools/device/server.py on this machine (10.0.2.2 from the emulator).
set -u
FORM=$1; APK=$2; OUT=$3
PKG=app.falak.player
DIR=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$OUT"
n=0

log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$OUT/walk.txt"; }
shot() { n=$((n + 1)); f=$(printf "%02d-%s" "$n" "$1"); adb exec-out screencap -p > "$OUT/$f.png"; log "shot $f"; [ "$FORM" = phone ] && python3 "$DIR/ui.py" dump "$OUT/$f.xml" > /dev/null 2>&1; true; }
key() { adb shell input keyevent "$@"; }
keys() { local c=$1 k=$2 d=${3:-0.35}; for _ in $(seq "$c"); do adb shell input keyevent "$k"; sleep "$d"; done; }
swipe() { adb shell input swipe "$@"; }
tap() { python3 "$DIR/ui.py" tap "$1" | tee -a "$OUT/walk.txt"; }
dumpui() { :; }  # shot keeps the phone's screen elements itself
gfx_start() { adb shell dumpsys gfxinfo $PKG reset > /dev/null; }
gfx_end() { adb shell dumpsys gfxinfo $PKG > "$OUT/gfx-$1.txt"; grep -E "Total frames|Janky frames|50th|90th|95th|99th|Slow UI|Frame deadline" "$OUT/gfx-$1.txt" | sed "s/^/  [$1] /" | tee -a "$OUT/walk.txt"; }
# Frames drawn in 8 s with nobody touching anything: a page at rest should draw almost none (an endless
# animation or a redraw loop shows here). The slideshow's turn and a clock's minute are a few.
idle_frames() { sleep 2; gfx_start; sleep 8; adb shell dumpsys gfxinfo $PKG > "$OUT/idle-$n.txt"; log "frames drawn in 8 s at rest: $(grep -m1 'Total frames' "$OUT/idle-$n.txt" | sed 's/.*: //')"; }
alive() { adb shell pidof $PKG > /dev/null && return 0; log "!! the app is not running"; return 1; }

W=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1); SW=${W%x*}; SH=${W#*x}
log "form $FORM, screen ${SW}x${SH}"

adb install -r -g "$APK" | tail -1 | tee -a "$OUT/walk.txt"
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2> /dev/null
adb logcat -c
# Android's one-time "Viewing full screen" note covers the player and takes Back: a person taps "Got it" once.
adb shell settings put secure immersive_mode_confirmations confirmed
START=$(date +%s)
adb shell am start -W -n $PKG/.BenchSeedActivity --es server http://10.0.2.2:8080 --es user u --es pass p | tee -a "$OUT/walk.txt"
# The lists (2,200 items) and the guide (4 MB) come from the test server; give them time.
sleep 35
shot start
alive
idle_frames

if [ "$FORM" = tv ]; then
  # Home: rows up and down, along a row, back to the top.
  gfx_start; keys 6 KEYCODE_DPAD_DOWN 0.5; keys 8 KEYCODE_DPAD_RIGHT 0.3; shot home-scrolled; keys 8 KEYCODE_DPAD_LEFT 0.3; keys 6 KEYCODE_DPAD_UP 0.5; gfx_end home
  shot home
  # The menu along the top: Up reaches it; Right to Live TV opens it after a short rest.
  keys 3 KEYCODE_DPAD_UP 0.5; shot menu
  for page in live films series; do
    key KEYCODE_DPAD_RIGHT; sleep 3; shot "$page"
    gfx_start; keys 6 KEYCODE_DPAD_DOWN 0.5; keys 8 KEYCODE_DPAD_RIGHT 0.3; shot "$page-scrolled"; keys 8 KEYCODE_DPAD_LEFT 0.3; keys 6 KEYCODE_DPAD_UP 0.5; gfx_end "$page"
    # Up past the chips and the panel to the menu (extra presses in the menu do nothing).
    keys 5 KEYCODE_DPAD_UP 0.4
  done
  alive
  # A film: back to Films, down to a poster, open it, play it.
  # (Down from the menu: the panel's buttons, the chips, then the first row's first poster.)
  key KEYCODE_DPAD_LEFT; sleep 3; keys 3 KEYCODE_DPAD_DOWN 0.6; key KEYCODE_DPAD_CENTER; sleep 5; shot film-page
  keys 3 KEYCODE_DPAD_DOWN 0.6; sleep 1; shot film-page-down; keys 3 KEYCODE_DPAD_UP 0.6; sleep 1; shot film-page-up
  key KEYCODE_DPAD_CENTER; sleep 10; shot film-playing
  key KEYCODE_DPAD_CENTER; sleep 1.5; shot film-controls
  key KEYCODE_BACK; sleep 2; key KEYCODE_BACK; sleep 3; shot after-film
  alive
  # Live TV: a channel plays, then the next ones.
  keys 3 KEYCODE_DPAD_UP 0.5; key KEYCODE_DPAD_LEFT; sleep 3; keys 2 KEYCODE_DPAD_DOWN 0.6; key KEYCODE_DPAD_CENTER; sleep 4; shot live-list-or-player
  key KEYCODE_DPAD_CENTER; sleep 8; shot live-playing
  key KEYCODE_CHANNEL_UP; sleep 5; key KEYCODE_CHANNEL_UP; sleep 6; shot live-zapped
  key KEYCODE_DPAD_CENTER; sleep 1.5; shot live-controls
  key KEYCODE_BACK; sleep 1; key KEYCODE_BACK; sleep 3; shot after-live
  alive
  # Search with the remote's search key, then typing.
  key KEYCODE_SEARCH; sleep 3; shot search-open
  adb shell input text "matrix"; sleep 3; shot search-typed
  key KEYCODE_DPAD_DOWN; sleep 1; shot search-down
  key KEYCODE_BACK; sleep 1; key KEYCODE_BACK; sleep 2
  # Settings: along the menu's tools to the gear.
  keys 4 KEYCODE_DPAD_UP 0.4; keys 12 KEYCODE_DPAD_RIGHT 0.5; sleep 1; shot menu-tools
  key KEYCODE_DPAD_CENTER; sleep 3; shot tool-opened
  gfx_start; keys 8 KEYCODE_DPAD_DOWN 0.4; keys 8 KEYCODE_DPAD_UP 0.4; gfx_end settings
  key KEYCODE_DPAD_RIGHT; sleep 1; shot settings-page
  key KEYCODE_BACK; sleep 2; shot after-settings
  # Home once more, everything loaded by now (the first time, the guide was still being read).
  keys 3 KEYCODE_DPAD_UP 0.4; keys 14 KEYCODE_DPAD_LEFT 0.3; sleep 2; shot home-again
  idle_frames
  keys 2 KEYCODE_DPAD_DOWN 0.6
  gfx_start; keys 6 KEYCODE_DPAD_DOWN 0.5; keys 8 KEYCODE_DPAD_RIGHT 0.3; keys 8 KEYCODE_DPAD_LEFT 0.3; keys 6 KEYCODE_DPAD_UP 0.5; gfx_end home-again
else
  # Phone: the lists by swiping, pages by their buttons.
  gfx_start; for _ in 1 2 3 4 5; do swipe $((SW / 2)) $((SH * 3 / 4)) $((SW / 2)) $((SH / 4)) 350; sleep 0.8; done; shot home-scrolled
  for _ in 1 2 3 4 5; do swipe $((SW / 2)) $((SH / 4)) $((SW / 2)) $((SH * 3 / 4)) 350; sleep 0.8; done; gfx_end home
  shot home; dumpui home
  for page in Live Films Series; do
    tap "$page"; sleep 3; slug=$(echo "$page" | tr 'A-Z ' 'a-z-'); shot "$slug"; dumpui "$slug"
    gfx_start; for _ in 1 2 3 4; do swipe $((SW / 2)) $((SH * 3 / 4)) $((SW / 2)) $((SH / 4)) 350; sleep 0.8; done
    swipe $((SW * 3 / 4)) $((SH / 2)) $((SW / 4)) $((SH / 2)) 300; sleep 0.8; shot "$slug-scrolled"
    for _ in 1 2 3 4; do swipe $((SW / 2)) $((SH / 4)) $((SW / 2)) $((SH * 3 / 4)) 350; sleep 0.8; done; gfx_end "$slug"
  done
  alive
  # A film: tap a poster, play it.
  tap Films; sleep 3; python3 "$DIR/ui.py" field | tee -a "$OUT/walk.txt"; sleep 2; adb shell input text "matrix"; sleep 4; shot search-films; key KEYCODE_BACK; sleep 1; tap "The Matrix"; sleep 4; shot film-page; dumpui film-page
  tap Play || tap Resume; sleep 10; shot film-playing
  adb shell input tap $((SW / 2)) $((SH / 2)); sleep 1.5; shot film-controls; dumpui film-controls
  key KEYCODE_BACK; sleep 3; key KEYCODE_BACK; sleep 2; shot after-film
  alive
  # Live TV: a channel.
  tap Live; sleep 3; tap "All channels"; sleep 3; tap "Entertainment 1"; sleep 9; shot live-playing
  adb shell input tap $((SW / 2)) $((SH / 2)); sleep 1.5; shot live-controls; dumpui live-controls
  key KEYCODE_BACK; sleep 3; shot after-live
  alive
  # Search, More, Settings.
  tap Home; sleep 2; python3 "$DIR/ui.py" field | tee -a "$OUT/walk.txt"; sleep 2; adb shell input text "matrix"; sleep 3; shot search; key KEYCODE_BACK; sleep 1; key KEYCODE_BACK; sleep 1
  tap More; sleep 2; shot more; dumpui more
  tap Settings; sleep 2; shot settings
  gfx_start; for _ in 1 2 3; do swipe $((SW / 2)) $((SH * 3 / 4)) $((SW / 2)) $((SH / 4)) 350; sleep 0.8; done; gfx_end settings
  tap Player; sleep 2; shot settings-player
  key KEYCODE_BACK; sleep 1; key KEYCODE_BACK; sleep 1
  # Side-on.
  adb shell settings put system accelerometer_rotation 0; adb shell settings put system user_rotation 1; sleep 3; shot landscape
  adb shell settings put system user_rotation 0; sleep 2
  # Home once more, everything loaded by now.
  tap Home; sleep 3; shot home-again
  idle_frames
  gfx_start; for _ in 1 2 3 4 5; do swipe $((SW / 2)) $((SH * 3 / 4)) $((SW / 2)) $((SH / 4)) 350; sleep 0.8; done
  for _ in 1 2 3 4 5; do swipe $((SW / 2)) $((SH / 4)) $((SW / 2)) $((SH * 3 / 4)) 350; sleep 0.8; done; gfx_end home-again
fi

alive
adb shell dumpsys meminfo $PKG | grep -E "TOTAL PSS|TOTAL RSS|Java Heap|Native Heap|Graphics" > "$OUT/memory.txt"
adb logcat -d > "$OUT/logcat.txt"
adb logcat -b crash -d > "$OUT/crash.txt"
{
  echo "form: $FORM, screen ${SW}x${SH}, walk took $(( $(date +%s) - START )) s"
  echo "app running at the end: $(adb shell pidof $PKG > /dev/null && echo yes || echo NO)"
  echo "crashes (FATAL EXCEPTION, ours): $(grep -c "FATAL EXCEPTION" "$OUT/crash.txt" 2> /dev/null)"
  echo "not responding (ANR in $PKG): $(grep -c "ANR in $PKG" "$OUT/logcat.txt")"
  # Android 14's freezer logs "froze <pid> <app>" for every app it pauses in the background: only ours count.
  echo "the system paused the app in the background: $(grep -i "froze" "$OUT/logcat.txt" | grep -c "$PKG")"
  echo "memory:"; cat "$OUT/memory.txt"
  echo "frames per scrolled page:"
  for f in "$OUT"/gfx-*.txt; do
    p=$(basename "$f" .txt); p=${p#gfx-}
    printf "  %-10s %s | %s | %s\n" "$p" "$(grep -m1 'Total frames' "$f" | sed 's/.*: //') frames" \
      "$(grep -m1 'Janky frames' "$f" | sed 's/.*: //') janky" "$(grep -m1 '90th' "$f" | sed 's/.*: //') at 90%"
  done
} | tee "$OUT/summary.txt"
