# Architecture and findings

How Boophis gets a full-screen video UI into Android Auto without mirroring the phone, and what
turned out to matter. Tested with Android Auto 18 on a phone running Android 13,
on the Desktop Head Unit (DHU) and in a real car.

## Rendering on the car screen

- Android Auto gives the app a `Surface` for the car screen. Boophis creates a private
  `VirtualDisplay` on it (flags `OWN_CONTENT_ONLY | PRESENTATION`) and shows a `Presentation` that
  hosts the same Compose UI as the phone, in `LayoutMode.Car`.
- Video plays in a `SurfaceView` inside that Presentation and needed no special handling. A
  `TextureView` fallback stays selectable in debug builds.
- When the surface is destroyed, the Presentation and VirtualDisplay are torn down. The saved-state
  bundle and ViewModelStore are carried over, so screen state survives app switching.
- Boophis uses no `MediaProjection`, no screen capture and no accessibility service.

## Car entry point

- A `CarActivity` from Google's Android Auto SDK (the legacy projection API) draws into a
  `SurfaceView` that feeds the renderer. Touches arrive as real `MotionEvent`s with positions and are
  passed on unchanged, so a swipe acts where the finger is.
- Steering-wheel keys and the dashboard media card come
  from the player's own `MediaSession`; Boophis has no `MediaBrowserService`. An earlier build added
  one, because a media app seemed to be required for Android Auto to list the package; what decides
  that is the install source (see the next section). The browse entry only opened Android Auto's
  audio player, which can't show video, so it was removed. On the DHU, play/pause and next still reach
  Boophis from the steering-wheel keys, and the media card still shows playback.
- `car/` also holds a Car App Library route: a navigation-category `CarAppService` drawing on the
  `NavigationTemplate` map surface, with scrolls turned into a centre-anchored DOWN / MOVE / UP
  gesture (the idea comes from Fermata). It isn't registered in the manifest, because the host
  reports scrolls without a position, so every swipe would start at the centre of the screen.
- That route and the Car App Library stay in the APK on purpose. A build without them was flagged by
  Play Protect's cloud scan ("This app may be harmful", generic malware) during the car-ready
  reinstall, while the same build with them scanned safe. (The car-ready reinstall is described in
  the next section; a plain `adb install` isn't cloud-scanned, so it never shows this.)

## How Android Auto decides whether to list a sideloaded app

Android Auto's validator (`CAR.VALIDATOR` in logcat) denies an app with `failed all other checks`
unless both of these are true:

1. `installerPackageName` is `com.android.vending` (Google Play), and
2. the install was *initiated* by the stock package installer. `adb install -i com.android.vending`
   sets (1), but records `com.android.shell` as the initiator, and is still denied.

The phone's own Android Auto *Customize launcher* list applies the same check, so you can verify an
install without a car. Findings:

- Installs through the stock installer with Play attribution are accepted with
  `packageSource` 3 (local file) and 4 (downloaded file) alike.
- Such installs don't need the developer setting *Unknown sources*.
- A plain `adb install` is denied whether or not *Unknown sources* is on.

The car-ready reinstall lives in `app/.../CarReadyInstaller.kt`:

- Boophis checks `getInstallSourceInfo()`. If the install doesn't qualify, it copies its own base APK
  to its cache and sends `ACTION_INSTALL_PACKAGE` with `EXTRA_INSTALLER_PACKAGE_NAME =
  com.android.vending` and `EXTRA_NOT_UNKNOWN_SOURCE` through a `FileProvider` URI.
- The intent is pinned to the system installer (`MATCH_SYSTEM_ONLY`), because other apps also handle
  this intent and would break the initiator check.
- The first time, Android asks the user to allow *Install unknown apps* for Boophis. The installer
  then continues by itself.
- Play Protect may offer a scan. Boophis passes it, as long as the Car App Library stays in the APK
  (see Car entry point).

## Library

- Library roots are the top-level `Movies`, `TV`, `Shows`, `TV Shows` on each mounted volume, plus any folders
  the user adds. Nested roots never index a file twice; the deepest root owns its subtree.
- Scans are incremental (path + size + mtime) and run on app start, on car connect, and on
  demand. Watch state is keyed by file and survives a folder being removed and re-added. Items on a
  missing SD card stay indexed.
- Artwork comes from Jellyfin/Kodi sidecars first, otherwise from a frame grab that skips dark or
  flat frames.
- Some Matroska files store the `Tracks` element after the clusters, which
  Media3's extractor cannot handle. `MkvFixingDataSource` presents a virtual file with `Tracks`
  moved into the padding before the first cluster. No offsets change and the file on disk is never
  touched.

## Car UI budget

- Android Auto streams the car screen as H.264. Full-screen motion costs bitrate and softens text,
  so in car mode the ambient glow is static and backdrops drift at half speed.
- Transitions stay at or under about 320 ms, because projected taps already carry latency.
- Targets are at least 76 dp, primary text is at least 24 sp, and input is tap and scroll only.
- Car screens range from 800×480 to 1920×720, plus Android Auto's split view, so layouts key off the
  visible area rather than the screen size.
- Video is never hidden or paused based on driving state.
