# Notice

Boophis is free software: GPL-3.0-or-later (see `LICENSE`).

## Third-party code and ideas

- [Fermata](https://github.com/AndreyPavlenko/Fermata) by Andrey Pavlenko, GPL-3.0.
  Boophis's car input bridge (`car/src/main/kotlin/app/boophis/car/CarInputBridge.kt`) follows the
  approach in Fermata's `fermata/src/auto/java/me/aap/fermata/auto/MirrorServiceFS.java`: an
  `Action.PAN` in the NavigationTemplate's map action strip so the host delivers scroll events, and
  scroll synthesis as a DOWN at the surface centre, MOVEs by negated distance, and an UP after an idle
  timeout. No Fermata code is copied verbatim; Fermata's screen-mirroring (MediaProjection) approach
  is deliberately not used.

- Fermata's legacy projection entry point (`fermata/src/auto/java/me/aap/fermata/auto/MirrorService.java`,
  `MirrorActivity.java`) is the model for `car-projection/` (CarActivityService + CarActivity with a
  SurfaceView). Boophis renders its own UI on its own VirtualDisplay; it does not mirror the phone.

## Proprietary component

- **Google Android Auto SDK** (`aauto.aar`, legacy projection API: `com.google.android.apps.auto.sdk`,
  `com.google.android.gms.car`) is proprietary, © Google. Only `car-projection/` uses it,
  so that Boophis can appear in real cars when sideloaded. It is not in this repository;
  `tools/fetch-aauto.sh` fetches the copy that ships with Fermata. Without it, Boophis builds and runs
  on the phone but has no Android Auto entry.

## Development tooling

- `scripts/identity_scan.py`, `scripts/dev_setup.py` and `.githooks/` come from IrisEcho (same
  author) and keep their AGPL-3.0-or-later license. They are developer tools and not part of the app.

## Libraries (all FOSS)

- AndroidX (Jetpack Compose, Room, Car App Library, Lifecycle, Activity, Core): Apache-2.0
- AndroidX Media3 / ExoPlayer: Apache-2.0
- Jellyfin Media3 FFmpeg decoder (`org.jellyfin.media3:media3-ffmpeg-decoder`): GPL-3.0 (FFmpeg LGPL/GPL components)
- Coil: Apache-2.0
- Kotlin, kotlinx.coroutines: Apache-2.0

## Fonts

- Bricolage Grotesque (titles), © The Bricolage Grotesque Project Authors: SIL Open Font License 1.1,
  see `licenses/fonts/BricolageGrotesque-OFL.txt`.
- Atkinson Hyperlegible Next (text), © The Atkinson Hyperlegible Next Project Authors: SIL Open Font
  License 1.1, see `licenses/fonts/AtkinsonHyperlegibleNext-OFL.txt`.
