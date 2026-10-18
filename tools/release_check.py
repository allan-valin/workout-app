#!/usr/bin/env python3
"""Scripted release check on the AVD `testphone` (Phase 41, 2026-10-18): one command, DB asserts,
no eyeballing. Run before every version Allan installs.

  ANDROID_SERIAL=emulator-5554 tools/release_check.py [--apk app/build/outputs/apk/release/app-release.apk]

Needs: the AVD booted (see MAINTENANCE.md), the CORE workout of the fase 2 plan on it
(Plank ↔ Russian Twist superset = workout_exercise 48/49, templates 122–127), adb root for
the DB pull. Taps use the 1080×2400 layout of that AVD; every step asserts the DB or the
screen, so a layout drift fails loudly instead of passing by accident.

Covers: S1 superset hand-over (Phase 35) · G1 goal edit keeps reps · G3 "last time" hint when the
profile changed · W1 done-row edit rewrites set_log · C1 own cadence only · end/save + "just this
time" restores the templates · G2 the archived copy "CORE 09/26" (same profile) prefills the 33 kg
logged in CORE · the saved session is deleted again at the end (sqlite3 on the AVD).
"""
import os, re, subprocess, sys, time, sqlite3, shutil, tempfile

ADB = "/home/allan/Android/Sdk/platform-tools/adb"
PKG = "dev.allan.workoutapp"
HERE = os.path.dirname(os.path.abspath(__file__))
UI = os.path.join(HERE, "emu_ui.py")
TMP = tempfile.mkdtemp(prefix="relcheck_")
fails = []

def adb(*a):
    return subprocess.run([ADB, *a], capture_output=True, text=True).stdout
def ui(*a):
    return subprocess.run([sys.executable, UI, *a], capture_output=True, text=True).stdout
def tap(label):
    out = ui("tap", label)
    if "NOT FOUND" in out: raise SystemExit(f"UI label not found: {label}")
def tapxy(x, y): adb("shell", "input", "tap", str(x), str(y)); time.sleep(1.2)
def swipe(x1, y1, x2, y2): adb("shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), "300"); time.sleep(1.2)
def dump(): return adb("exec-out", "uiautomator", "dump", "/dev/stdout")
def nodes():
    """(text, x1, y1) per on-screen node with a text, attribute order independent."""
    out = []
    for n in re.findall(r'<node[^>]*>', dump()):
        t = re.search(r'text="([^"]*)"', n); b = re.search(r'bounds="\[(\d+),(\d+)\]', n)
        if t and t.group(1) and b: out.append((t.group(1), int(b.group(1)), int(b.group(2))))
    return out

def texts():
    return re.findall(r'(?:text|content-desc)="([^"]+)"', dump())
def first(*labels):
    """First label present on screen (en / pt-BR)."""
    t = texts()
    for l in labels:
        if any(l in x for x in t): return l
    raise SystemExit(f"none of {labels} on screen; saw {t[:12]}")
def dismiss_dialogs():
    """Fresh-install dialogs on the AVD: notification permission, HyperOS battery onboarding."""
    for _ in range(3):
        t = texts()
        hit = next((l for l in ("Allow", "Permitir", "Close", "Fechar", "Later", "Depois", "Not now", "Agora não", "OK") if l in t), None)
        if not hit: return
        tap(hit); time.sleep(1.5)

def db():
    for f in ("workout.db", "workout.db-wal", "workout.db-shm"):
        adb("pull", f"/data/data/{PKG}/databases/{f}", os.path.join(TMP, f))
    c = sqlite3.connect(os.path.join(TMP, "workout.db"))
    return c
def check(name, ok, detail=""):
    print(("PASS " if ok else "FAIL ") + name + (f"  [{detail}]" if detail else ""))
    if not ok: fails.append(name)

def main():
    apk = sys.argv[sys.argv.index("--apk") + 1] if "--apk" in sys.argv else None
    adb("root"); time.sleep(2)
    if apk:
        print(adb("install", "-r", apk).strip().splitlines()[-1])
    ver = re.search(r"versionName=(\S+)", adb("shell", "dumpsys", "package", PKG))
    print("version", ver.group(1) if ver else "?")
    adb("shell", "pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS")
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "monkey", "-p", PKG, "1"); time.sleep(3)

    tap(first("Ativos", "Active")); tap("CORE")
    if first("Iniciar Treino", "Start Workout", "Retomar treino", "Resume workout").startswith(("Retomar", "Resume")):
        # a session left RUNNING by an aborted run: end + discard it first
        tap(first("Encerrar treino", "End workout")); tap(first("Descartar treino", "Discard workout")); time.sleep(1)
        if any(x in ("Descartar", "Discard", "Just this time") for x in texts()): tap(first("Descartar", "Discard", "Just this time"))
        time.sleep(2); adb("shell", "input", "keyevent", "4"); time.sleep(1)
        if not any("CORE" in x for x in texts()[:3]): tap("CORE")
    tap(first("Iniciar Treino", "Start Workout")); time.sleep(2)
    dismiss_dialogs()
    tap(first("Rotação Russa", "Russian Twist")); time.sleep(1.5)
    check("session screen on Russian Twist", any("Russian" in t or "Rotação Russa" in t for t in texts()))

    # G1: goal of set 3 10 -> 15, reps input must stay 10. Rows are located from the dump
    # (the English layout sits ~40 px higher than the pt-BR one: shorter superset line).
    metas = sorted([n for n in nodes() if n[0].isdigit() and 700 <= n[1] <= 760], key=lambda n: n[2])
    check("three target cells found", len(metas) >= 3, str(metas))
    tapxy(metas[2][1] + 60, metas[2][2] + 60); tapxy(344, 1205)
    adb("shell", "input", "keyevent", "KEYCODE_MOVE_END"); adb("shell", "input", "keyevent", "KEYCODE_DEL", "KEYCODE_DEL")
    adb("shell", "input", "text", "15"); tap(first("Salvar", "Save")); time.sleep(1.5)
    ns = nodes()
    meta3 = next((n for n in ns if n[0] == "15" and 700 <= n[1] <= 760), None)
    reps3 = [v for v, x, y in ns if 550 <= x <= 600 and meta3 and abs(y - meta3[2]) < 80]
    check("G1 goal 15 keeps reps 10", meta3 is not None and reps3 == ["10"], f"meta {meta3} reps {reps3}")

    # S1: Plank 1 logged from the bottom button -> pager hands over to the partner (Russian Twist)
    swipe(200, 1500, 900, 1500); time.sleep(1)
    check("on Plank page", any("Prancha" in t or "Plank" in t for t in texts()))
    tapxy(855, 1927); time.sleep(2)
    check("S1 after Plank 1 the pager is on Russian Twist", any("Russian" in t or "Rotação Russa" in t for t in texts()[:6]))
    for _ in range(13): adb("shell", "input", "tap", "415", "1233")   # RT set 1: 20 -> 33 kg (distinctive)
    time.sleep(1)
    check("RT set 1 at 33 kg", any(v == "33" for v, x, y in nodes() if 240 <= x <= 300), str([n for n in nodes() if 240 <= n[1] <= 300][:3]))
    tapxy(855, 1927); time.sleep(2)   # RT 1 -> back to Plank, rest running
    t = texts()
    check("S1 after RT 1 the pager is back on Plank with rest", any("Prancha" in x or "Plank" in x for x in t[:6]) and any(x in ("Descanso", "Rest") for x in t))

    # W1: + on the DONE Plank row 1 -> set_log rewritten
    tapxy(415, 1233); time.sleep(2)
    c = db()
    sid = c.execute("select max(id) from session").fetchone()[0]
    w = c.execute("select weightKg from set_log where sessionId=? and workoutExerciseId=48 and setIndex=0", (sid,)).fetchone()
    check("W1 done-row edit rewrote set_log", w is not None and abs(w[0] - 1.0) < 1e-9, f"set_log 48/0 = {w}")

    # C1: Plank cadence 3-0-1-0; Russian Twist page must not show it
    tap(first("Editar cadência", "Edit cadence", "Cadência:", "Cadence:")); tapxy(245, 1204); time.sleep(1)
    for x, d in ((245, "3"), (385, "0"), (526, "1"), (667, "0")):
        tapxy(x, 852); adb("shell", "input", "keyevent", "KEYCODE_MOVE_END"); adb("shell", "input", "keyevent", "KEYCODE_DEL"); adb("shell", "input", "text", d)
    tapxy(821, 853); time.sleep(1.5)
    check("C1 Plank shows its cadence", any("3-0-1-0" in x for x in texts()))
    swipe(900, 1500, 200, 1500); time.sleep(1)
    t = texts()
    check("C1 Russian Twist shows no cadence", any("Russian" in x or "Rotação Russa" in x for x in t) and not any("3-0-1-0" in x for x in t))

    # end: SAVE the session, "just this time" for the plan edits -> templates restored
    tapxy(1005, 220); tap(first("Encerrar treino", "End workout", "End Workout")); tap(first("Salvar treino", "Save workout", "Save Workout")); time.sleep(1)
    tap(first("Descartar", "Discard", "Just this time")); time.sleep(2)
    c = db()
    st = c.execute("select status from session where id=?", (sid,)).fetchone()
    tm = c.execute("select count(*) from set_template where workoutExerciseId in (48,49) and (tempo!='' or targetValue!=10 and workoutExerciseId=49)").fetchone()[0]
    check("save: session FINISHED", st == ("FINISHED",), str(st))
    check("save: templates restored", tm == 0, f"{tm} rows differ")
    check("save: RT 1 logged at 33 kg", c.execute("select weightKg from set_log where sessionId=? and workoutExerciseId=49 and setIndex=0", (sid,)).fetchone() == (33.0,))

    # G2: the archived copy "CORE 09/26" has the same profile for Russian Twist -> prefills 33 kg
    adb("shell", "input", "keyevent", "4"); time.sleep(1); adb("shell", "input", "keyevent", "4"); time.sleep(1)
    adb("shell", "am", "force-stop", PKG); adb("shell", "monkey", "-p", PKG, "1"); time.sleep(3)
    tap(first("Arquivo", "Archive")); time.sleep(1); tap(first("Treinos", "Workouts")); time.sleep(1)
    tap("CORE 09/26"); time.sleep(1)
    tap(first("Iniciar Treino", "Start Workout")); time.sleep(2); dismiss_dialogs()
    tap(first("Rotação Russa", "Russian Twist")); time.sleep(1.5)
    w1 = [v for v, x, y in nodes() if 240 <= x <= 300 and 1100 <= y <= 1250]
    check("G2 same profile in another workout prefills 33 kg", w1[:1] == ["33"], f"row 1 weight {w1}")
    sid2 = db().execute("select max(id) from session").fetchone()[0]
    # G3: change the copy's goal -> its profile differs now, no history of its own -> hint with CORE's last
    metas = sorted([n for n in nodes() if n[0].isdigit() and 700 <= n[1] <= 760], key=lambda n: n[2])
    tapxy(metas[2][1] + 60, metas[2][2] + 60); tapxy(344, 1205)
    adb("shell", "input", "keyevent", "KEYCODE_MOVE_END"); adb("shell", "input", "keyevent", "KEYCODE_DEL", "KEYCODE_DEL")
    adb("shell", "input", "text", "15"); tap(first("Salvar", "Save")); time.sleep(1.5)
    t = texts()
    check("G3 hint names the other profile and its last session", any(("Last time" in x or "Última vez" in x) and "3×10:" in x and "33 kg × 10" in x for x in t),
          str([x for x in t if "ast time" in x or "ltima vez" in x]))
    tapxy(1005, 220); tap(first("Encerrar treino", "End workout", "End Workout")); tap(first("Descartar treino", "Discard workout", "Discard Workout")); time.sleep(2)
    if any(x in ("Descartar", "Discard", "Just this time") for x in texts()): tap(first("Descartar", "Discard", "Just this time")); time.sleep(1)

    # cleanup: the saved CORE session must not change future prefills -> delete it on the AVD
    adb("shell", "am", "force-stop", PKG)
    sql = f"delete from set_log where sessionId in ({sid},{sid2}); delete from session where id in ({sid},{sid2}); delete from session_set_draft;"
    adb("shell", "sqlite3", f"/data/data/{PKG}/databases/workout.db", f"'{sql}'")
    time.sleep(1)
    # read back through the device's own sqlite3: a host-side read of a just-pulled WAL is stale
    left = adb("shell", "sqlite3", f"/data/data/{PKG}/databases/workout.db", f"'select count(*) from session where id in ({sid},{sid2});'").strip()
    check("cleanup: test sessions removed", left == "0", f"{left} left")
    shutil.rmtree(TMP, ignore_errors=True)
    print("\n" + ("ALL PASS" if not fails else f"{len(fails)} FAILED: {fails}"))
    sys.exit(1 if fails else 0)

if __name__ == "__main__":
    main()
