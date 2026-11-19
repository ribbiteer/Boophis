# Boophis fixtures

All titles, plots and artwork are invented. Art and clips were generated locally with IrisEcho
(JSON prompts in `assets/prompts/`); the art is exported by `fixtures/art.py`.
No real posters, logos, studios or characters are imitated.

## Tree (`fixtures/media/`)

```
Movies/Paper Harbor (2021)/        Paper Harbor (2021).mkv (3:30, AAC stereo eng, 440 Hz), poster.jpg, fanart.jpg, movie.nfo
Movies/Midnight Orchard (2018)/    Midnight Orchard (2018).mp4 (60 s, AC3 5.1 eng, 523 Hz), folder.jpg, backdrop.jpg, Midnight Orchard (2018).nfo
Movies/The Quiet Engine (2023)/    The Quiet Engine (2023).mkv (45 s) only: no art, no nfo (frame-grab + filename fallback)
Shows/Saltmarsh Station/           tvshow.nfo, poster.jpg, fanart.jpg, season01-poster.jpg, season02-poster.jpg
  Season 01/ Season 02/            "Saltmarsh Station SxxEyy - Title".mkv + .nfo + -thumb.jpg, 3 each
```

The Quiet Engine tracks: video; audio 1 Japanese (default, 330 Hz); audio 2 English (660 Hz);
subtitle 1 English SRT; subtitle 2 English SRT flagged forced. All carry language tags.

Episodes: 40 s each, AAC stereo English, tones 262/294/349/392/494/587 Hz (S01E01..S02E03).
S01E02 "Late Arrival" is 3:00 (resume rule). Paper Harbor at 3:30 is the other > 2 min clip.

Art: posters 680x1020, backdrops 1280x720, episode thumbs 640x360 (JPEG, each < 250 KB).

## Recreating clips

`bash fixtures/make_clips.sh` (needs ffmpeg on PATH; run from anywhere). The picture comes from
a 6–8 s clip in `fixtures/media/_clips/`, looped to the title's length. Those clips were made with
IrisEcho (LTX image-to-video) from each title's own artwork, so the video matches its poster; their
prompts are `assets/prompts/clip-*.json`. They're gitignored like all fixture video. Without them,
the script makes a slow zoom into the committed still instead: `fanart.jpg`, `backdrop.jpg`, the
episode thumbs, and `fixtures/art-src/quiet-engine-still.jpg` for The Quiet Engine, which has no
artwork of its own. Either way the video is 1280x720 at 24 fps, libx264 CRF 23, with no text burned
in.

Audio is `sine` tones encoded as AAC 64k stereo (AC3 384k 5.1 via `pan` for Midnight Orchard). The
Quiet Engine is muxed from two sine inputs plus two generated SRT files with
`-disposition`/`-metadata:s:*` tags. `*.mkv`/`*.mp4` are gitignored; art and NFOs are committed.
To re-export art: `python fixtures/art.py` (reads `assets/work/fix/`, Pillow required).
