#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Recreates the fixture video clips (gitignored) in fixtures/media/. Requires ffmpeg on PATH.
# Usage: bash fixtures/make_clips.sh
set -euo pipefail
cd "$(dirname "$0")/media"

# Video comes from short generated clips in media/_clips/ (IrisEcho, LTX image-to-video from each
# title's own artwork; gitignored, prompts in assets/prompts/clip-*.json), looped to the clip length.
# Without them, a slow zoom into the committed still is used instead. Either way: 1280x720, 24 fps,
# no burned-in text.
V() { # $1=clip name in _clips/  $2=fallback still  $3=duration (s): prints the ffmpeg input args
  if [ -f "_clips/$1.mp4" ]; then
    echo "-stream_loop -1 -t $3 -i _clips/$1.mp4"
  else
    echo "-loop 1 -framerate 24 -t $3 -i $2"
  fi
}
VF() { # $1=clip name  $2=duration (s)
  if [ -f "_clips/$1.mp4" ]; then
    echo "scale=1280:720:force_original_aspect_ratio=increase,crop=1280:720,fps=24,format=yuv420p"
  else
    local step
    step=$(awk "BEGIN { printf \"%.7f\", 0.12 / ($2 * 24) }")
    echo "scale=2560:-2,zoompan=z='min(zoom+$step,1.12)':d=1:x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':s=1280x720:fps=24,format=yuv420p"
  fi
}
X264=(-c:v libx264 -preset medium -crf 23 -maxrate 1500k -bufsize 3000k -pix_fmt yuv420p -g 48)
SINE() { echo "sine=frequency=$1:sample_rate=48000:duration=$2"; }

# Stills are copied to space-free names, because V's output is split on whitespace.
S=$(mktemp -d); command -v cygpath >/dev/null && S=$(cygpath -m "$S")   # native ffmpeg on Windows
cp "Movies/Paper Harbor (2021)/fanart.jpg" "$S/paper-harbor.jpg"
cp "Movies/Midnight Orchard (2018)/backdrop.jpg" "$S/midnight-orchard.jpg"
cp ../art-src/quiet-engine-still.jpg "$S/quiet-engine.jpg"

# Paper Harbor: 3m30s, MKV, AAC stereo, eng, 440 Hz
d="Movies/Paper Harbor (2021)"
ffmpeg -y -v error $(V paper-harbor "$S/paper-harbor.jpg" 210) -f lavfi -i "$(SINE 440 210)" -vf "$(VF paper-harbor 210)" \
  "${X264[@]}" -c:a aac -b:a 64k -ac 2 -metadata:s:a:0 language=eng -metadata title="Paper Harbor" "$d/Paper Harbor (2021).mkv"

# Midnight Orchard: 60s, MP4, AC3 5.1, 523 Hz
d="Movies/Midnight Orchard (2018)"
ffmpeg -y -v error $(V midnight-orchard "$S/midnight-orchard.jpg" 60) -f lavfi -i "$(SINE 523 60)" -vf "$(VF midnight-orchard 60)" \
  -af "pan=5.1|FL=c0|FR=c0|FC=c0|LFE=0.3*c0|BL=c0|BR=c0" "${X264[@]}" -c:a ac3 -b:a 384k -metadata:s:a:0 language=eng \
  -movflags +faststart "$d/Midnight Orchard (2018).mp4"

# The Quiet Engine: 45s MKV, 2 audio (jpn default 330 Hz, eng 660 Hz), 2 subs (eng, eng forced).
# No artwork sits next to it on purpose (frame-grab fallback); its picture comes from fixtures/art-src/.
d="Movies/The Quiet Engine (2023)"
T=$(mktemp -d); command -v cygpath >/dev/null && T=$(cygpath -m "$T")
cat > "$T/full.srt" <<'SRT'
1
00:00:02,000 --> 00:00:06,000
The engine has stopped humming.

2
00:00:08,000 --> 00:00:12,000
That is what worries me.

3
00:00:20,000 --> 00:00:24,000
Listen. Do you hear it?
SRT
cat > "$T/forced.srt" <<'SRT'
1
00:00:08,500 --> 00:00:11,500
[In the old tongue] Wake the engine.

2
00:00:30,000 --> 00:00:33,000
[In the old tongue] It is done.
SRT
ffmpeg -y -v error $(V quiet-engine "$S/quiet-engine.jpg" 45) -f lavfi -i "$(SINE 330 45)" -f lavfi -i "$(SINE 660 45)" \
  -i "$T/full.srt" -i "$T/forced.srt" \
  -vf "$(VF quiet-engine 45)" -map 0:v -map 1:a -map 2:a -map 3 -map 4 \
  "${X264[@]}" -c:a aac -b:a 64k -ac 2 -c:s srt \
  -metadata:s:a:0 language=jpn -metadata:s:a:0 title="Japanese" -disposition:a:0 default \
  -metadata:s:a:1 language=eng -metadata:s:a:1 title="English" -disposition:a:1 0 \
  -metadata:s:s:0 language=eng -metadata:s:s:0 title="English" -disposition:s:0 0 \
  -metadata:s:s:1 language=eng -metadata:s:s:1 title="English (Forced)" -disposition:s:1 forced \
  "$d/The Quiet Engine (2023).mkv"
rm -rf "$T"

# Episodes: 40s MKV AAC eng (S01E02 is 3 min), tone per episode, picture from the episode's clip/thumb
ep() { # season ep title freq dur
  local base="Shows/Saltmarsh Station/Season 0$1/Saltmarsh Station S0$1E0$2 - $3" c="s0$1e0$2"
  cp "$base-thumb.jpg" "$S/$c.jpg"
  ffmpeg -y -v error $(V "$c" "$S/$c.jpg" "$5") -f lavfi -i "$(SINE "$4" "$5")" -vf "$(VF "$c" "$5")" \
    "${X264[@]}" -c:a aac -b:a 64k -ac 2 -metadata:s:a:0 language=eng "$base.mkv"
}
ep 1 1 "The Lantern Keeper" 262 40
ep 1 2 "Late Arrival"       294 180
ep 1 3 "Unclaimed Parcels"  349 40
ep 2 1 "Frozen Line"        392 40
ep 2 2 "Signal in the Rain" 494 40
ep 2 3 "The Waiting Room"   587 40
rm -rf "$S"
echo done
