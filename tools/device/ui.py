#!/usr/bin/env python3
"""Taps an element on the emulator's screen by its text or description (8.54, phone walk).

  python3 ui.py tap "Films"            exact text / description, else the first that contains it
  python3 ui.py dump out.xml           the screen's elements (for the report)

Uses uiautomator; retries while the screen is still animating. Exit 1 when nothing matched.
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


def adb(*a, check=False):
    return subprocess.run(["adb", *a], capture_output=True, text=True, check=check)


def dump():
    for _ in range(4):
        r = adb("shell", "uiautomator", "dump", "--compressed", "/sdcard/ui.xml")
        if "dumped" in (r.stdout + r.stderr):
            x = adb("shell", "cat", "/sdcard/ui.xml").stdout
            if x.strip().startswith("<?xml"):
                return x
        time.sleep(1.0)
    return ""


def nodes(xml):
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return []
    out = []
    for n in root.iter("node"):
        b = re.findall(r"\d+", n.get("bounds", ""))
        if len(b) != 4:
            continue
        x1, y1, x2, y2 = map(int, b)
        if x2 <= x1 or y2 <= y1:
            continue
        out.append((n.get("text", ""), n.get("content-desc", ""), (x1 + x2) // 2, (y1 + y2) // 2))
    return out


def tap(label):
    ns = nodes(dump())
    exact = [n for n in ns if label in (n[0], n[1])]
    loose = [n for n in ns if label.lower() in (n[0] + " " + n[1]).lower()]
    pick = (exact or loose or [None])[0]
    if pick is None:
        print("no element: %s" % label)
        return 1
    adb("shell", "input", "tap", str(pick[2]), str(pick[3]))
    print("tapped %s at %d,%d" % (label, pick[2], pick[3]))
    return 0


if __name__ == "__main__":
    if sys.argv[1] == "tap":
        sys.exit(tap(sys.argv[2]))
    if sys.argv[1] == "dump":
        x = dump()
        open(sys.argv[2], "w").write(x)
        sys.exit(0 if x else 1)
