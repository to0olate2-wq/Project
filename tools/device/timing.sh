#!/bin/bash
# Big playlist timing (8.57): how long a big playlist takes to open, first downloaded and then from the
# saved copy, and how long the screen stalls after. Run by the device job instead of walk.sh when a
# commit's first line has [big]; server.py then serves SCALE=8 (~8,000 channels, 43,000 films, 4,800 series).
#
#   timing.sh tv|phone <apk> <out dir>
#
# Reads the app's own "playlist ready" line (log tag FalakTiming) and Android's "Skipped N frames"
# (the main thread was busy that long). Leaves screenshots, gfx-*.txt, logcat.txt and summary.txt.
set -u
FORM=$1; APK=$2; OUT=$3
PKG=app.falak.player
mkdir -p "$OUT"
n=0
log() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$OUT/walk.txt"; }
shot() { n=$((n + 1)); f=$(printf "%02d-%s" "$n" "$1"); adb exec-out screencap -p > "$OUT/$f.png"; log "shot $f"; }
keys() { local c=$1 k=$2 d=${3:-0.35}; for _ in $(seq "$c"); do adb shell input keyevent "$k"; sleep "$d"; done; }
gfx_start() { adb shell dumpsys gfxinfo $PKG reset > /dev/null; }
gfx_end() { adb shell dumpsys gfxinfo $PKG > "$OUT/gfx-$1.txt"; grep -E "Total frames|Janky frames:|90th percentile|Slow UI" "$OUT/gfx-$1.txt" | sed "s/^/  [$1] /" | tee -a "$OUT/walk.txt"; }

# Waits for the app's "playlist ready" line (up to $1 s); prints how long it took from now.
wait_ready() {
  local limit=$1 t0 line
  t0=$(date +%s%N)
  for _ in $(seq $((limit * 2))); do
    line=$(adb logcat -d -s FalakTiming:I | grep "playlist ready" | tail -1)
    if [ -n "$line" ]; then
      log "ready after $(( ($(date +%s%N) - t0) / 1000000 )) ms (wall clock): ${line#*: }"
      return 0
    fi
    sleep 0.5
  done
  log "!! not ready after $limit s"
  return 1
}

# Main-thread stalls Android noted since the log was cleared: count, total and the biggest.
stalls() {
  adb logcat -d | grep "Skipped [0-9]* frames" | grep -o "Skipped [0-9]*" | awk '{s+=$2; if ($2>m) m=$2; c++} END {printf "%d stalls, %d frames skipped in all, biggest %d frames (~%d ms)\n", c, s, m, m*16.7}'
}

W=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1); SW=${W%x*}; SH=${W#*x}
log "form $FORM, screen ${SW}x${SH}, big playlist"
adb install -r -g "$APK" | tail -1 | tee -a "$OUT/walk.txt"
adb shell settings put secure immersive_mode_confirmations confirmed

# 1. First time: downloaded from the provider.
adb logcat -c
adb shell am start -n $PKG/.BenchSeedActivity --es server http://10.0.2.2:8080 --es user u --es pass p > /dev/null
wait_ready 300
log "stalls in the first 20 s: $(sleep 20; stalls)"
shot first-open
adb logcat -d > "$OUT/logcat-first.txt"

# 2. Home, then Films: moving around the big lists.
gfx_start
if [ "$FORM" = tv ]; then
  keys 2 KEYCODE_DPAD_DOWN 0.6; keys 10 KEYCODE_DPAD_RIGHT 0.3; keys 4 KEYCODE_DPAD_DOWN 0.5
else
  for _ in 1 2 3 4; do adb shell input swipe $((SW / 2)) $((SH * 3 / 4)) $((SW / 2)) $((SH / 4)) 350; sleep 0.8; done
fi
gfx_end home
adb logcat -c
if [ "$FORM" = tv ]; then
  keys 6 KEYCODE_DPAD_UP 0.4; keys 2 KEYCODE_DPAD_RIGHT 0.5; sleep 4
else
  python3 "$(dirname "$0")/ui.py" tap Films | tee -a "$OUT/walk.txt"; sleep 4
fi
log "stalls opening Films: $(stalls)"
shot films
adb logcat -c
if [ "$FORM" = tv ]; then keys 1 KEYCODE_DPAD_LEFT 0.5; keys 1 KEYCODE_DPAD_LEFT 0.5; sleep 4; else python3 "$(dirname "$0")/ui.py" tap Live | tee -a "$OUT/walk.txt"; sleep 4; fi
log "stalls opening Live TV: $(stalls)"
shot live

# 3. Opened again three times from the saved copy (the app closed in between).
for i in 1 2 3; do
  adb shell am force-stop $PKG
  sleep 2
  adb logcat -c
  adb shell am start -n $PKG/app.falak.player.ui.MainActivity > /dev/null
  wait_ready 120
  log "stalls in the first 15 s (opening $i): $(sleep 15; stalls)"
  [ $i = 1 ] && shot reopen
done
adb logcat -d > "$OUT/logcat.txt"
adb shell dumpsys meminfo $PKG | grep -E "TOTAL PSS|TOTAL:|Java Heap|Native Heap" > "$OUT/memory.txt"
{
  echo "app running at the end: $(adb shell pidof $PKG > /dev/null && echo yes || echo NO)"
  echo "crashes: $(adb logcat -d -b crash | grep -c "FATAL EXCEPTION")"
  grep -E "ready after|stalls" "$OUT/walk.txt"
  cat "$OUT/memory.txt"
} > "$OUT/summary.txt"
cat "$OUT/summary.txt"
