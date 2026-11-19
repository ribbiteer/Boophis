#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Installs an APK on the phone THROUGH KingInstaller (stock installer + Play attribution), driving its
# UI over adb, so Android Auto in a real car accepts the app. Declines Play Protect's upload.
# usage: tools/king-install.sh path/to.apk
set -e
export MSYS_NO_PATHCONV=1
apk=${1:-dist/boophis-0.1.0.apk}
adb push "$apk" /sdcard/Download/boophis-0.1.0.apk >/dev/null
adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null; }
has() { adb shell cat /sdcard/ui.xml | grep -q "$1"; }
tapt() { local b; b=$(adb shell cat /sdcard/ui.xml | grep -oE "(text|content-desc)=\"$1\"[^>]*bounds=\"[^\"]+\"" | head -1 | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]'); [ -z "$b" ] && return 1; read x1 y1 x2 y2 <<<$(echo $b | tr '[],' '   '); adb shell input tap $(( (x1+x2)/2 )) $(( (y1+y2)/2 )); }
adb shell am force-stop com.example.kinginstaller
adb shell monkey -p com.example.kinginstaller -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 3
dump; if has 'text="Update Available"'; then tapt "Cancel"; sleep 1.5; dump; fi
tapt "Select APK to install:"; sleep 2.5; dump
tapt "Search"; sleep 1; adb shell input text boophis-0.1.0; adb shell input keyevent KEYCODE_ENTER; sleep 2.5; dump
tapt "boophis-0.1.0.apk"; sleep 2; dump
tapt "Install as king"; sleep 3; dump
tapt "Update" || tapt "Install"; sleep 6; dump
if has "Play Protect"; then tapt "More details"; sleep 1.5; dump; has "Install without scanning" && adb shell input tap 330 1475; sleep 7; dump; fi
has 'text="App installed."' && echo "INSTALLED" || { echo "NOT CONFIRMED:"; adb shell cat /sdcard/ui.xml | grep -oE 'text="[^"]+"' | head -5; }
tapt "Done" || true
adb shell rm -f /sdcard/ui.xml
adb shell appops set io.github.ribbiteer.boophis MANAGE_EXTERNAL_STORAGE allow
adb shell dumpsys package io.github.ribbiteer.boophis | grep -E "installerPackageName|packageSource|lastUpdateTime" | sort -u | sed 's/^ */  /'
