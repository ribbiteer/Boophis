# Contributing

Thanks for helping. Boophis keeps settings to a minimum, so before adding one, look for a default
that covers the case without asking the user.

## One-time setup

```sh
python scripts/dev_setup.py      # enables .githooks, checks your commit identity, creates a private denylist
git config user.name  "<github-handle>"
git config user.email "<id>+<github-handle>@users.noreply.github.com"
```

- Commits must be signed off (`git commit -s`) and use a GitHub noreply address.
- Run with `TZ=UTC` so commit timestamps don't reveal your timezone.
- The hooks run `scripts/identity_scan.py`, which blocks home-directory paths, private IP addresses,
  real email addresses, tokens and image metadata. Your private denylist
  (`~/.config/boophis/identity-denylist.txt`) adds strings that identify you. It lives outside the
  repo.

## Build and test

See the README. Before every commit:

```sh
./gradlew :ui:testDebugUnitTest :core-model:test :core-library:testDebugUnitTest assembleDebug
```

## Rules

- Kotlin and Jetpack Compose only. Every source file gets the SPDX header
  (`GPL-3.0-or-later`) and the copyright line used in existing files.
- No internet permission, no Google Play Services. The only proprietary dependency is Google's
  Android Auto SDK, confined to `car-projection/`.
- Car UI:
  - targets at least 76 dp, primary text at least 24 sp, tap and scroll only;
  - respect the visible-area insets;
  - never hide or pause video based on speed, gear or driving state.
- No screen mirroring, MediaProjection or accessibility tricks.
- Docs, tests and screenshots use only the invented titles in `fixtures/`, never real media.
- Never commit keystores, `keystore.properties`, `local.properties`, `car-projection/libs/*.aar`, or
  video files under `fixtures/media/`.

## Dev loop with the Desktop Head Unit

1. Phone: Android Auto → ⋮ → *Start head unit server* (developer mode).
2. `adb forward tcp:5277 tcp:5277`
3. `python tools/dhu_driver.py tools/dhu/p720_8in.ini` (1280×720 @ 200 dpi). `tools/dhu/` has
   profiles for other head units (800×480, 6-inch, very wide, 1080p, ultrawide, portrait, insets); see
   its README. A bare name such as `default.ini` loads from the DHU's own `config/` folder. Append console
   commands (`tap x y`, `screenshot <path>`, `keycode media_next`, `quit`) to `tmp/dhu/cmd.txt`.
4. Drags: `python tools/dhu_drag.py x1 y1 x2 y2 [ms] [steps]`.
5. `bash tools/aa-check.sh` shows whether Android Auto lists the app (it restarts Android Auto, which
   also stops the head unit server).

Debug builds accept:

```sh
adb shell am broadcast -a app.boophis.DEBUG_PLAY -p io.github.ribbiteer.boophis --es title "'Paper Harbor'" --ez car true --el seek 150000
adb shell am broadcast -a app.boophis.DEBUG_SNAPSHOT -p io.github.ribbiteer.boophis
adb shell am start -n io.github.ribbiteer.boophis/app.boophis.debug.CarPreviewActivity --ei dpw 800 --ei dph 450
```

The last one previews car layouts on the phone without a DHU.

## Test media

`fixtures/` holds an invented library: three films (*Midnight Orchard*, *Paper Harbor*, *The Quiet
Engine*) and one show (*Saltmarsh Station*). `bash fixtures/make_clips.sh` recreates its video clips
with ffmpeg. [fixtures/README.md](fixtures/README.md) says where their pictures come from.
