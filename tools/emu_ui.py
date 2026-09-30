#!/usr/bin/env python3
"""Headless-emulator UI helper used for the per-batch emulator passes (2026-09-30).

  dump                      texts/content-descs with tap centres (* = checked)
  tap <text-or-desc> [i]    tap the i-th node whose text/desc matches (exact, then substring)
  tapxy x y | swipe x1 y1 x2 y2 | back | text <str> | shot <name>   (SHOT_DIR, default /tmp)

Notes learnt the hard way: the uiautomator dump lists only on-screen nodes; snackbars last
4 s, so tap their action by coordinates without a dump in between; a dialog moves when the
keyboard opens, so locate OK after typing; `input text` needs a focused field.
"""
import subprocess, sys, re, time
ADB = "/home/allan/Android/Sdk/platform-tools/adb"
import os
S = os.environ.get("SHOT_DIR", "/tmp")
def sh(*a): return subprocess.run([ADB, *a], capture_output=True, text=True).stdout
def nodes():
    sh("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    x = sh("shell", "cat", "/sdcard/ui.xml")
    out = []
    for m in re.finditer(r'<node[^>]*?>', x):
        n = m.group(0)
        t = re.search(r'text="([^"]*)"', n).group(1)
        d = re.search(r'content-desc="([^"]*)"', n).group(1)
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if not b: continue
        x1,y1,x2,y2 = map(int, b.groups())
        chk = 'checked="true"' in n
        out.append((t, d, (x1+x2)//2, (y1+y2)//2, x1,y1,x2,y2, chk))
    return out
def find(label, idx=0):
    hits = [n for n in nodes() if n[0] == label or n[1] == label]
    if not hits: hits = [n for n in nodes() if label.lower() in (n[0]+n[1]).lower()]
    return hits[idx] if len(hits) > idx else None
cmd = sys.argv[1]
if cmd == "dump":
    for t,d,cx,cy,*_,chk in nodes():
        if t or d: print(f"{cx},{cy}  {'*' if chk else ' '} {t[:60]!r} {('desc='+d[:40]) if d else ''}")
elif cmd == "tap":
    n = find(sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 0)
    if not n: print("NOT FOUND:", sys.argv[2]); sys.exit(1)
    sh("shell", "input", "tap", str(n[2]), str(n[3])); print("tapped", n[0] or n[1], n[2], n[3]); time.sleep(1.2)
elif cmd == "tapxy":
    sh("shell", "input", "tap", sys.argv[2], sys.argv[3]); time.sleep(1.2)
elif cmd == "swipe":
    sh("shell", "input", "swipe", *sys.argv[2:6], "300"); time.sleep(1.2)
elif cmd == "back":
    sh("shell", "input", "keyevent", "4"); time.sleep(1.0)
elif cmd == "text":
    sh("shell", "input", "text", sys.argv[2]); time.sleep(0.5)
elif cmd == "shot":
    subprocess.run(f"{ADB} exec-out screencap -p > {S}/{sys.argv[2]}.png", shell=True); print(f"{S}/{sys.argv[2]}.png")
