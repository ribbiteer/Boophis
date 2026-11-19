#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Copies Google's legacy Android Auto SDK (aauto.aar, proprietary) into car-projection/libs/.
# Source: the copy shipped in Fermata's repository (ref/fermata, or its GitHub raw URL).
# Needed for the Android Auto entry point (car-projection/). Never commit the .aar.
set -e
cd "$(dirname "$0")/.."
dst=car-projection/libs/aauto.aar
mkdir -p car-projection/libs
if [ -f ref/fermata/fermata/lib/auto/aauto.aar ]; then
  cp ref/fermata/fermata/lib/auto/aauto.aar "$dst"
else
  curl -fsSL -o "$dst" https://github.com/AndreyPavlenko/Fermata/raw/master/fermata/lib/auto/aauto.aar
fi
ls -la "$dst"
