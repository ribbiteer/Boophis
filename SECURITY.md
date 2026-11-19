# Security

Please report security problems privately through GitHub: on the repository page, use
**Security → Report a vulnerability**. Don't open a public issue for them.

Boophis has no internet permission and no accounts. The places where a bug could matter are:

- the files it reads: malformed video or sidecar files (`.nfo`, images, subtitles) could crash the
  player or the scanner.
- the car-ready reinstall: Boophis only ever installs its own APK, through the system installer
  with the user's confirmation.
- debug hooks: the exported adb broadcast receivers exist only in debug builds.

Only the latest release gets fixes.
