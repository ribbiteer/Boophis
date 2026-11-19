# SPDX-License-Identifier: GPL-3.0-or-later
# Puts Boophis on the USB-connected phone so Android Auto in a REAL CAR accepts it.
#
# Android Auto (real cars, not the DHU) only lists a sideloaded app if:
#   - installerPackageName = com.android.vending, AND
#   - the install was *requested by the stock Package Installer* (not com.android.shell).
# `adb install` always records the shell as requester, so it can't satisfy the second rule.
# KingInstaller (https://github.com/fcaronte/KingInstaller, already on the phone) routes the install
# through the system installer with Play attribution — that's what Fermata uses too.
#
# Usage (PowerShell, from the project folder):  .\tools\install-phone.ps1 [path\to.apk]
# Then on the phone: KingInstaller > Select APK > boophis-0.1.0.apk > Install as king > Update.
# If Play Protect offers a scan: More details > "Install without scanning" (nothing is sent to Google).
param([string]$Apk = "dist\boophis-0.1.0.apk")

$ErrorActionPreference = "Stop"
if (-not (Test-Path $Apk)) { throw "APK not found: $Apk" }

adb push $Apk /sdcard/Download/boophis-0.1.0.apk
if ($LASTEXITCODE -ne 0) { throw "adb push failed" }
adb shell monkey -p com.example.kinginstaller -c android.intent.category.LAUNCHER 1 | Out-Null

Write-Host "KingInstaller is open on the phone: Select APK -> boophis-0.1.0.apk -> Install as king -> Update."
Write-Host "Afterwards run:  adb shell appops set io.github.ribbiteer.boophis MANAGE_EXTERNAL_STORAGE allow"
Write-Host "Check:          adb shell dumpsys package io.github.ribbiteer.boophis | Select-String 'installerPackageName|packageSource'"
Write-Host "                (want com.android.vending and packageSource=4)"
