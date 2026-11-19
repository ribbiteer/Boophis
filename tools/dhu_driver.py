# SPDX-License-Identifier: GPL-3.0-or-later
"""Scriptable Desktop Head Unit: launches DHU and forwards lines appended to a command file to its console.

usage: python tools/dhu_driver.py [config.ini | path/to/profile.ini] [--cmd tmp/dhu/cmd.txt] [--log tmp/dhu/dhu.log]
Append DHU console commands (e.g. "tap 400 240", "screenshot F:/x.png", "quit") to the command file.
"""
import argparse, os, subprocess, sys, time

ap = argparse.ArgumentParser()
ap.add_argument("config", nargs="?", default="default.ini")
ap.add_argument("--cmd", default="tmp/dhu/cmd.txt")
ap.add_argument("--log", default="tmp/dhu/dhu.log")
a = ap.parse_args()

dhu_dir = os.path.join(os.environ.get("LOCALAPPDATA", os.path.join(os.path.expanduser("~"), "AppData", "Local")), "Android", "Sdk", "extras", "google", "auto")
exe = os.path.join(dhu_dir, "desktop-head-unit.exe")
cmd_file, log_file = os.path.abspath(a.cmd), os.path.abspath(a.log)
os.makedirs(os.path.dirname(cmd_file), exist_ok=True)
open(cmd_file, "w").close()
log = open(log_file, "w", buffering=1)
cfg = os.path.abspath(a.config) if os.path.isabs(a.config) or os.path.exists(a.config) else os.path.join(dhu_dir, "config", a.config)
p = subprocess.Popen([exe, "-c", cfg], cwd=dhu_dir, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, text=True)
done = 0
try:
    while p.poll() is None:
        with open(cmd_file) as f:
            lines = f.read().splitlines()
        for line in lines[done:]:
            log.write(f"\n>>> {line}\n")
            p.stdin.write(line + "\n"); p.stdin.flush()
        done = len(lines)
        time.sleep(0.2)
finally:
    log.write(f"\n[dhu exited {p.poll()}]\n")
