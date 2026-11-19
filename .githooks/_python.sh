# SPDX-License-Identifier: AGPL-3.0-or-later
# Dev tooling copied from IrisEcho (same author); not part of the app. The app is GPL-3.0-or-later.
# Sourced by the hooks: run a Python 3 script with whatever interpreter exists.
run_py() {
  if python3 -c "" >/dev/null 2>&1; then
    python3 "$@"
  elif python -c "" >/dev/null 2>&1; then
    python "$@"
  elif command -v uv >/dev/null 2>&1; then
    uv run --quiet --no-project python "$@"
  else
    echo "boophis hooks: no Python 3 found; install uv or Python 3.11+" >&2
    exit 1
  fi
}
