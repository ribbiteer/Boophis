# SPDX-License-Identifier: GPL-3.0-or-later
"""Mouse drag on the DHU window in head-unit pixel coordinates (Windows only).
usage: python tools/dhu_drag.py x1 y1 x2 y2 [duration_ms] [steps]   (head-unit px, e.g. 800x480)
"""
import ctypes, sys, time
from ctypes import wintypes
u = ctypes.windll.user32
try: ctypes.windll.shcore.SetProcessDpiAwareness(2)
except Exception: u.SetProcessDPIAware()
HU_W = 800  # overridden by --w
args = [a for a in sys.argv[1:] if not a.startswith("--")]
for a in sys.argv[1:]:
    if a.startswith("--w="): HU_W = int(a[4:])
x1, y1, x2, y2 = map(float, args[:4]); dur = float(args[4]) if len(args) > 4 else 300; steps = int(args[5]) if len(args) > 5 else 20
found = []
@ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
def cb(h, _):
    n = u.GetWindowTextLengthW(h)
    if n and u.IsWindowVisible(h):
        b = ctypes.create_unicode_buffer(n + 1); u.GetWindowTextW(h, b, n + 1)
        if "Head Unit" in b.value or "Desktop Head" in b.value: found.append((h, b.value))
    return True
u.EnumWindows(cb, 0)
if not found: sys.exit("DHU window not found")
h = found[0][0]
r = wintypes.RECT(); u.GetClientRect(h, ctypes.byref(r))
pt = wintypes.POINT(0, 0); u.ClientToScreen(h, ctypes.byref(pt))
s = r.right / HU_W
u.ShowWindow(h, 9); u.SetForegroundWindow(h); time.sleep(0.15)
def mv(x, y): u.SetCursorPos(int(pt.x + x * s), int(pt.y + y * s))
mv(x1, y1); time.sleep(0.05); u.mouse_event(0x0002, 0, 0, 0, 0)
for i in range(1, steps + 1):
    mv(x1 + (x2 - x1) * i / steps, y1 + (y2 - y1) * i / steps); time.sleep(dur / 1000 / steps)
u.mouse_event(0x0004, 0, 0, 0, 0)
print(f"dragged on '{found[0][1]}' client={r.right}x{r.bottom} scale={s:.2f}")
