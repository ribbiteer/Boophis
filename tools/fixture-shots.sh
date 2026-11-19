#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Screenshots with the fictional fixture library only, beside (never instead of) the installed app.
#
#   bash tools/fixture-shots.sh build      debug build with app ID ...boophis.shots (own database + settings)
#   bash tools/fixture-shots.sh setup      push fixtures/media to /sdcard/BoophisFixtures, install the .shots
#                                          build, point its library at the fixtures only, skip first-run setup
#   bash tools/fixture-shots.sh teardown   uninstall the .shots build and delete /sdcard/BoophisFixtures
#
# Needs fixtures/media clips (bash fixtures/make_clips.sh) and `. tools/env.sh`. The installed Boophis, its
# library and its watch state are never touched. Debug routes: tools/nav.sh with PKG=io.github.ribbiteer.boophis.shots.
set -euo pipefail
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")/.."
PKG=io.github.ribbiteer.boophis.shots
DEV=/sdcard/BoophisFixtures
VOL=/storage/emulated/0
APK=app/build/outputs/apk/debug/app-debug.apk

case "${1:-}" in
build)
  ./gradlew -q assembleDebug -Pboophis.idSuffix=.shots
  ;;
setup)
  adb shell mkdir -p "$DEV"
  adb push fixtures/media/Movies "$DEV/" >/dev/null
  adb push fixtures/media/Shows "$DEV/" >/dev/null
  adb install -r "$APK" >/dev/null
  adb shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow
  adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
  adb shell am force-stop "$PKG"
  # Library: the fixture folders only; every auto-detected folder on the phone switched off.
  folders="A	MOVIES	$VOL/BoophisFixtures/Movies&#10;A	SHOWS	$VOL/BoophisFixtures/Shows&#10;"
  for d in Movies TV Shows "TV Shows"; do
    adb shell "[ -d '$VOL/$d' ]" && folders="${folders}D	$VOL/$d&#10;"
  done
  lib="<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map><string name=\"folders\">$folders</string></map>"
  setup="<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map><boolean name=\"setup_finished\" value=\"true\" /><boolean name=\"car_card_dismissed\" value=\"true\" /></map>"
  adb shell "run-as $PKG mkdir -p shared_prefs"
  printf '%s' "$lib" | adb shell "run-as $PKG sh -c 'cat > shared_prefs/boophis_library.xml'"
  printf '%s' "$setup" | adb shell "run-as $PKG sh -c 'cat > shared_prefs/boophis_setup.xml'"
  # NOLAUNCH=1: leave it unopened (e.g. to start a screen recording before its first launch)
  [ -n "${NOLAUNCH:-}" ] || adb shell am start -n "$PKG/app.boophis.MainActivity" >/dev/null
  echo "ready: $PKG shows only $DEV"
  ;;
teardown)
  adb uninstall "$PKG" >/dev/null 2>&1 || true
  adb shell rm -rf "$DEV"
  echo "removed $PKG and $DEV"
  ;;
*)
  sed -n 3,10p "$0"; exit 1
  ;;
esac
