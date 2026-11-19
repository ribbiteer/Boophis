#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Restarts Android Auto on the phone, opens Customize launcher, prints which Boophis/Fermata entries
# AA lists and its validator verdicts for Boophis. No car needed.
# NOTE: force-stopping AA also stops its "head unit server", so the DHU can't connect afterwards
# ("Don't have video focus"). Do DHU checks BEFORE this script, or restart the server after it
# (AA settings > More options > Start head unit server).
export MSYS_NO_PATHCONV=1
adb logcat -c
adb shell am force-stop com.google.android.projection.gearhead
adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard
adb shell am start -n com.google.android.projection.gearhead/.companion.settings.DefaultSettingsActivity >/dev/null; sleep 3
for i in 1 2 3 4; do adb shell uiautomator dump /sdcard/ui.xml >/dev/null; adb shell cat /sdcard/ui.xml | grep -qiE 'text="Customi[sz]e launcher"' && break; adb shell input swipe 540 1700 540 800 300; sleep 1; done
b=$(adb shell cat /sdcard/ui.xml | grep -oiE 'text="Customi[sz]e launcher"[^>]*bounds="[^"]+"' | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]')
read x1 y1 x2 y2 <<<$(echo $b | tr '[],' '   '); adb shell input tap $(( (x1+x2)/2 )) $(( (y1+y2)/2 )); sleep 3
all=""; for i in 1 2 3 4 5; do adb shell uiautomator dump /sdcard/ui.xml >/dev/null; all="$all $(adb shell cat /sdcard/ui.xml | grep -oE 'text="[^"]+"' | tr '\n' ' ')"; adb shell input swipe 540 1800 540 700 300; sleep 1; done
adb shell rm -f /sdcard/ui.xml
echo "LISTED: $(echo "$all" | grep -oE 'text="(Boophis[^"]*|Fermata[^"]*)"' | sort -u | tr '\n' ' ')"
adb logcat -d | grep "CAR.VALIDATOR" | grep -i boophis | cut -c32-200 | sort -u
echo "NOTE: Android Auto was restarted; its head unit server (DHU) is now stopped."
