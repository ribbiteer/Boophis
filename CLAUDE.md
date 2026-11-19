# CLAUDE.md — Boophis

Standing rules for working on this codebase (humans and coding agents alike). If a
`CLAUDE.local.md` exists next to this file, it holds machine-specific notes and takes precedence.

## How to work
- Commit at every green checkpoint with a clear message. Never commit keystores,
  `keystore.properties`, `local.properties`, `ref/`, or `fixtures/media/` video files.
- Keep docs and screenshots to the fictional fixture library (`fixtures/`), never real media.
- Never push without the maintainer's explicit permission for that push. Never rewrite or
  force-push published history.
- Coding agents credit themselves as co-author with a `Co-Authored-By:` trailer on their commits.
- Build and test: `. tools/env.sh` (Git Bash) then
  `./gradlew :ui:testDebugUnitTest :core-model:test assembleRelease`.

## Code rules
- Kotlin only, Compose only (no XML layouts except the Car App manifest bits).
- No network permission, no Google Play Services. The only proprietary dependency is Google's
  Android Auto SDK, confined to `car-projection/`.
- Keep androidx.car.app and the unregistered template route in car/: without them Play Protect's
  cloud scan flags the APK (ARCHITECTURE.md).
- GPL-3.0-or-later headers on source files. Fermata-derived code keeps attribution (NOTICE.md).
- No new user-facing settings without a strong reason. Prefer good defaults.
- Car-mode UI: ≥ 76dp targets, tap + scroll only, respect the visible-area insets.
- No driving restrictions: never hide or pause video based on speed, gear or driving state.
- No mirroring: no MediaProjection/screencast, no phone-screen capture, no accessibility tricks.
  The app renders its own UI to a private VirtualDisplay on the car surface.
