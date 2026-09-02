"""
Read a gesture log off the phone and say what to change.

    python analyze.py                 # pulls the log via adb, then analyses
    python analyze.py gestures.jsonl  # analyse a file you already have

The interesting records are the near-misses — gestures that were attempted and
didn't fire. A threshold is only wrong if real attempts are landing on the wrong
side of it, and that's exactly what these measure.
"""

import json
import pathlib
import statistics
import subprocess
import sys
from collections import Counter, defaultdict

ADB = r"D:\android-tools\sdk\platform-tools\adb.exe"
REMOTE = "/sdcard/Android/data/com.gesturemouse/files/gestures.jsonl"
LOCAL = pathlib.Path(__file__).parent / "gestures.jsonl"


def pull():
    print(f"pulling {REMOTE}")
    r = subprocess.run([ADB, "pull", REMOTE, str(LOCAL)],
                       capture_output=True, text=True)
    if "1 file pulled" not in (r.stdout + r.stderr):
        sys.exit(f"could not pull the log:\n{r.stdout}\n{r.stderr}\n"
                 "Open the Air tab at least once so the file exists.")
    return LOCAL


def load(path):
    events = []
    for line in pathlib.Path(path).read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            events.append(json.loads(line))
        except json.JSONDecodeError:
            continue          # a torn last line if the app died mid-write
    return events


def pct(values, p):
    if not values:
        return 0
    s = sorted(values)
    i = min(int(len(s) * p / 100), len(s) - 1)
    return s[i]


def spread(name, values, unit=""):
    if not values:
        return f"  {name}: none"
    return (f"  {name}: n={len(values)}  "
            f"min={min(values):.0f}{unit}  "
            f"p50={pct(values, 50):.0f}{unit}  "
            f"p90={pct(values, 90):.0f}{unit}  "
            f"max={max(values):.0f}{unit}")


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else pull()
    events = load(path)
    if not events:
        sys.exit("log is empty — use the Air tab for a bit first")

    kinds = Counter(e["k"] for e in events)
    by = defaultdict(list)
    for e in events:
        by[e["k"]].append(e)

    cfg = next((e for e in events if e["k"] == "session"), {})
    tap_max = cfg.get("tapMax", 600)
    pinch_close = cfg.get("pinchClose", 0.38)

    span = (max(e["t"] for e in events) - min(e["t"] for e in events)) / 1000.0
    print("=" * 66)
    print(f"  {len(events)} events over {span/60:.1f} min")
    print(f"  thresholds in effect: TAP_MAX={tap_max}ms  PINCH_CLOSE={pinch_close}")
    print("=" * 66)

    # ---- what happened -----------------------------------------------------
    print("\nGESTURES")
    for k in ("click", "rclick", "grab", "drop", "scroll", "pad_down", "gear"):
        if kinds.get(k):
            print(f"  {k:<16} {kinds[k]}")

    print("\nNEAR-MISSES  (attempted, did not fire)")
    misses = ("click_slow", "click_debounced", "pinch_near", "pinch_short", "overshoot")
    if not any(kinds.get(k) for k in misses):
        print("  none — nothing was fighting the thresholds")
    for k in misses:
        if kinds.get(k):
            print(f"  {k:<16} {kinds[k]}")

    findings = []

    # ---- click timing ------------------------------------------------------
    clicks = [e["ms"] for e in by["click"]]
    slow = [e["ms"] for e in by["click_slow"]]
    print("\nCLICK TIMING")
    print(spread("fired", clicks, "ms"))
    print(spread("too slow (read as lift-off)", slow, "ms"))

    if slow:
        rate = len(slow) / (len(slow) + len(clicks)) * 100
        would_fix = pct(slow, 90)
        print(f"  {rate:.0f}% of press attempts were read as lift-offs")
        if rate > 15:
            findings.append(
                f"Raise TAP_MAX from {tap_max}ms to about {int(would_fix / 50 + 1) * 50}ms — "
                f"{len(slow)} presses ({rate:.0f}%) overshot it and were treated as "
                f"lifting off the pad. p90 of the failures is {would_fix:.0f}ms.")
    if clicks:
        margin = pct(clicks, 90)
        if margin < tap_max * 0.4:
            findings.append(
                f"TAP_MAX could come DOWN to ~{int(margin * 1.6 / 50 + 1) * 50}ms. "
                f"90% of your clicks finish inside {margin:.0f}ms, so the current "
                f"{tap_max}ms mostly delays lift-off rather than helping clicks.")

    if kinds.get("click_debounced"):
        findings.append(
            f"{kinds['click_debounced']} clicks were swallowed by the CLICK_GAP "
            f"debounce. If those were deliberate double-clicks, lower CLICK_GAP.")

    # ---- pinch -------------------------------------------------------------
    near = [e["min"] for e in by["pinch_near"]]
    print("\nPINCH")
    print(f"  grabs: {kinds.get('grab', 0)}")
    if near:
        print(f"  reached for a pinch but never closed: {len(near)}, "
              f"closest approach p50={pct(near, 50):.2f} (threshold {pinch_close})")
        if len(near) > max(3, kinds.get("grab", 0) * 0.3):
            suggest = round(pct(near, 60) + 0.02, 2)
            findings.append(
                f"Raise PINCH_CLOSE from {pinch_close} to ~{suggest}. "
                f"{len(near)} pinch attempts stopped just short of closing.")
    short = [e["ms"] for e in by["pinch_short"]]
    if short:
        print(spread("  released before it grabbed", short, "ms"))
        if len(short) > max(3, kinds.get("grab", 0) * 0.5):
            findings.append(
                f"{len(short)} pinches were released before DRAG_HOLD elapsed. "
                f"If those were meant as clicks, that gesture is being used wrong; "
                f"if meant as grabs, lower DRAG_HOLD.")

    # ---- movement ----------------------------------------------------------
    print("\nMOVEMENT")
    over = by["overshoot"]
    per_gear = Counter(e.get("gear", "?") for e in over)
    clutches = kinds.get("pad_up", 0)
    print(f"  overshoot corrections: {len(over)}  {dict(per_gear)}")
    print(f"  clutches (lift + reposition): {clutches}"
          + (f"  ≈{clutches / (span/60):.1f}/min" if span > 60 else ""))
    gear_switches = kinds.get("gear", 0)
    print(f"  gear switches: {gear_switches}")

    if per_gear.get("precise", 0) > 8:
        findings.append(
            f"{per_gear['precise']} overshoots in the PRECISE gear — it is too fast "
            f"for fine work. Lower PRECISE_GAIN (0.55) or the speed slider.")
    if per_gear.get("sweep", 0) > 12:
        findings.append(
            f"{per_gear['sweep']} overshoots in the SWEEP gear. Lower SWEEP_GAIN (2.0).")
    if span > 120 and clutches / (span / 60) > 12:
        findings.append(
            f"Clutching {clutches/(span/60):.0f} times a minute — the hand keeps "
            f"running out of room. Raise the speed slider so fewer strokes are needed.")

    # ---- tracking quality --------------------------------------------------
    fps = [e["fps"] for e in by["tracking"] if "fps" in e]
    scales = [e["handScale"] for e in by["tracking"] if "handScale" in e]
    print("\nTRACKING")
    print(spread("fps", fps))
    if scales:
        print(f"  hand size in frame: p50={pct(scales, 50):.3f} "
              f"(fraction of frame height)")
    if fps and pct(fps, 50) < 15:
        findings.append(
            f"Median {pct(fps,50):.0f} fps is low. Every timing threshold gets "
            f"coarse at that rate — a 600ms window is only ~9 samples.")
    if scales and pct(scales, 50) < 0.06:
        findings.append(
            "Your hand is small in frame, which makes finger-extension checks "
            "noisy. Hold the hand closer to the phone.")

    # ---- verdict -----------------------------------------------------------
    print("\n" + "=" * 66)
    print("  RECOMMENDATIONS")
    print("=" * 66)
    if findings:
        for i, f in enumerate(findings, 1):
            print(f"\n{i}. {f}")
    else:
        print("\n  Nothing is fighting the current thresholds. If something still")
        print("  feels wrong, it is likely a gesture-design issue rather than a")
        print("  tuning one — describe what felt off.")
    print()


if __name__ == "__main__":
    main()
