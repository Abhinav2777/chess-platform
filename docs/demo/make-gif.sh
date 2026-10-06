#!/usr/bin/env bash
# Cuts the two recordings of frontend/e2e/demo.mjs into the README GIFs (Phase 10.6).
#
#   docs/demo/make-gif.sh frontend/e2e/out/demo
#
# The two videos start at different moments (each with its own browser page), so every cut is
# made at mark − that video's own start (marks.json). Clip 1 plays at real speed. Clip 2 plays the
# moves and the reconnect at real speed and the waits (new pods starting, the drain finishing) at
# 4×; a "4×" badge is drawn while it is fast. Output: docs/demo/{1-play,2-rolling-restart}.gif.
set -euo pipefail
DIR=${1:?usage: make-gif.sh <demo output dir>}
OUT=$(cd "$(dirname "$0")" && pwd)
WIDTH=${WIDTH:-1400}   # final width of the side-by-side GIF
FPS=${FPS:-8}

# Segments as "start_ms end_ms speed", from the marks.
read -r -d '' SEGMENTS < <(python3 - "$DIR/marks.json" <<'PY'
import json, sys
m = json.load(open(sys.argv[1]))["marks"]
clip1 = [(m["clip1-start"], m["clip1-end"], 1)]
r, d, x, e = m["restart"], m["drain"], m["replaced"], m["clip2-end"]
clip2 = [(m["clip2-start"], r + 3000, 1),        # the game, then the restart command
         (r + 3000, d - 2000, 4),                # new pods starting; old ones still serving
         (d - 2000, d + 7000, 1),                # the drain and the reconnect, at real speed
         (d + 7000, x - 1000, 4),                # the second old pod draining
         (x - 1000, x + 5000, 1),                # replaced; the same game plays on
         (x + 5000, e - 3000, 4),                # the rest of the line
         (e - 3000, e, 1)]                       # end on a settled board: both sides agree
for name, segs in (("clip1", clip1), ("clip2", clip2)):
    print(name, " ".join(f"{a}:{b}:{s}" for a, b, s in segs if b > a))
PY
) || true
START_LEFT=$(python3 -c "import json;print(json.load(open('$DIR/marks.json'))['videoStart']['left'])")
START_RIGHT=$(python3 -c "import json;print(json.load(open('$DIR/marks.json'))['videoStart']['right'])")

render() {   # render <name> <segments...>
  local name=$1; shift
  local tmp; tmp=$(mktemp -d)
  local i=0 list=""
  for seg in "$@"; do
    IFS=: read -r a b speed <<<"$seg"
    local la rb dur
    la=$(python3 -c "print(max(0,($a-$START_LEFT)/1000))")
    rb=$(python3 -c "print(max(0,($a-$START_RIGHT)/1000))")
    dur=$(python3 -c "print(($b-$a)/1000)")
    local badge=""
    [[ $speed != 1 ]] && badge=",drawtext=text='${speed}×':x=w-90:y=18:fontsize=30:fontcolor=white:box=1:boxcolor=0x6aa84f@0.9:boxborderw=8"
    ffmpeg -nostdin -v error -y -ss "$la" -t "$dur" -i "$DIR/left.webm" -ss "$rb" -t "$dur" -i "$DIR/right.webm" \
      -filter_complex "[0:v][1:v]hstack=inputs=2,setpts=PTS/$speed,fps=$FPS,scale=$WIDTH:-1:flags=lanczos$badge" \
      -an -c:v libx264 -preset veryfast -crf 18 "$tmp/$i.mp4"
    list+="file '$tmp/$i.mp4'"$'\n'
    i=$((i + 1))
  done
  printf '%s' "$list" > "$tmp/list.txt"
  ffmpeg -nostdin -v error -y -f concat -safe 0 -i "$tmp/list.txt" -c copy "$tmp/all.mp4"
  ffmpeg -nostdin -v error -y -i "$tmp/all.mp4" -vf "palettegen=stats_mode=diff:max_colors=128" "$tmp/palette.png"
  ffmpeg -nostdin -v error -y -i "$tmp/all.mp4" -i "$tmp/palette.png" \
    -lavfi "paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle" -loop 0 "$OUT/$name.gif"
  cp "$tmp/all.mp4" "$DIR/$name.mp4"
  rm -rf "$tmp"
  echo "$OUT/$name.gif  $(du -h "$OUT/$name.gif" | cut -f1)  $(ffprobe -v error -show_entries format=duration -of csv=p=0 "$DIR/$name.mp4" | cut -d. -f1) s"
}

while read -r name segs; do
  case $name in
    clip1) render 1-play $segs ;;
    clip2) render 2-rolling-restart $segs ;;
  esac
done <<<"$SEGMENTS"
