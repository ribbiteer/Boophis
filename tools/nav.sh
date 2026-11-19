#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Debug builds: open a route on car + phone.  tools/nav.sh show "Saltmarsh Station" | movies | about | home
r=$1; shift; t="$*"
if [ -n "$t" ]; then MSYS_NO_PATHCONV=1 adb shell am broadcast -a app.boophis.DEBUG_NAV -p "${PKG:-io.github.ribbiteer.boophis}" --es route "$r" --es title "'$t'" >/dev/null
else MSYS_NO_PATHCONV=1 adb shell am broadcast -a app.boophis.DEBUG_NAV -p "${PKG:-io.github.ribbiteer.boophis}" --es route "$r" >/dev/null; fi
