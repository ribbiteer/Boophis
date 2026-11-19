#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# DHU screenshot via the driver: tools/shot.sh name  → tmp/screens/name.png
echo "screenshot $(cygpath -m "$PWD")/tmp/screens/$1.png" >> tmp/dhu/cmd.txt; sleep 1.8
