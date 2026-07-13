#!/usr/bin/env python3
"""Generate app/src/main/assets/body/ SVGs from react-native-body-highlighter path data.

Source: https://github.com/HichamELBSI/react-native-body-highlighter (MIT, see LICENSE here).
bodyFront.ts / bodyBack.ts are vendored verbatim next to this script.

Outputs (same filenames the app already loads — ui/common/BodyMap.kt):
  front.svg / back.svg              male body, neutral grays (structural vs muscle)
  muscle-<wgerId>.svg               the wger muscle's region(s), black fill (tinted at runtime)
  front-f.svg / back-f.svg / muscle-<wgerId>-f.svg   same set for the female model

ViewBoxes are NOT taken from the library wrappers: their four views give the figure a
different share of the box (male 86% of height, female front 93%, female back 99%), which
rendered the female model bigger than the male and front/back misaligned (Allan 2026-07-13).
Instead each view's viewBox is computed from the figure's real bounding box so every figure
fills the same fraction of a box with the same aspect ratio — BodyMap.kt renders all views
with one BODY_ASPECT constant that must match ASPECT below.

Run from the repo root:  python3 tools/body-highlighter/convert.py
"""
import re
from pathlib import Path

HERE = Path(__file__).parent
OUT = HERE / "../../app/src/main/assets/body"

# Normalized geometry (from the original male view): figure occupies 85.9% of the
# viewBox height with a 6.6% top margin, in a width:height = 0.5 box.
ASPECT = 0.5
FIGURE_FRACTION = 0.8593
TOP_FRACTION = 0.0664

# Neutral body colors: structural parts slightly darker than muscle shapes so the
# figure reads on both light and dark themes without any runtime tint.
STRUCT_FILL = "#6F7276"
MUSCLE_FILL = "#8B8E93"
STRUCTURAL = {"head", "hair", "neck", "hands", "feet", "ankles", "knees"}

# wger muscle id -> react-native-body-highlighter slug(s), per view.
# Approximations (wger has no exact region in the set): serratus(3)->obliques,
# brachialis(13)->forearm, soleus(15)->calves (shared with gastrocnemius 7),
# lats(12)->upper-back.
FRONT_MAP = {
    1: ["biceps"],
    2: ["deltoids"],
    3: ["obliques"],
    4: ["chest"],
    6: ["abs"],
    10: ["quadriceps"],
    13: ["forearm"],
    14: ["obliques"],
}
BACK_MAP = {
    5: ["triceps"],
    7: ["calves"],
    8: ["gluteal"],
    9: ["trapezius"],
    11: ["hamstring"],
    12: ["upper-back"],
    15: ["calves"],
}


def parse(ts_path: Path) -> dict[str, list[str]]:
    """slug -> list of SVG path strings, in file order."""
    slugs: dict[str, list[str]] = {}
    current = None
    for line in ts_path.read_text().splitlines():
        m = re.search(r'slug:\s*"([^"]+)"', line)
        if m:
            current = m.group(1)
            slugs.setdefault(current, [])
            continue
        # Male files start paths with "M", female with "m " (relative commands).
        for path in re.findall(r'"([Mm][ \d.,-][^"]*)"', line):
            if current is not None:
                slugs[current].append(path)
    return slugs


_TOK = re.compile(r"([MmLlHhVvCcSsQqTtAaZz])|(-?\d*\.?\d+(?:e-?\d+)?)")


def _path_points(d: str) -> list[tuple[float, float]]:
    """All anchor + control points of a path (loose bbox — good enough for margins)."""
    seq = [(t[0] or None, t[1] or None) for t in _TOK.findall(d)]
    pts: list[tuple[float, float]] = []
    i = 0
    cmd = None
    cx = cy = sx = sy = 0.0

    def nums(n: int) -> list[float]:
        nonlocal i
        vals: list[float] = []
        while len(vals) < n:
            c, v = seq[i]
            assert c is None, f"expected number, got command {c!r}"
            vals.append(float(v))
            i += 1
        return vals

    def arc_end() -> tuple[float, float]:
        # rx ry rot laf sf x y — the two flags are single digits, often fused ("01-.36").
        nonlocal i
        nums(3)
        flags = 0
        while flags < 2:
            c, v = seq[i]
            assert c is None
            take = 2 - flags if len(v) >= 2 - flags and "." not in v[: 2 - flags] and all(ch in "01" for ch in v[: 2 - flags]) else 1
            rest = v[take:]
            i += 1
            if rest:
                seq.insert(i, (None, rest))
            flags += take
        x, y = nums(2)
        return x, y

    while i < len(seq):
        c, _ = seq[i]
        if c is not None:
            cmd = c
            i += 1
            if cmd in "Zz":
                cx, cy = sx, sy
                continue
        rel = cmd.islower()
        upper = cmd.upper()
        if upper == "M":
            x, y = nums(2)
            if rel:
                x += cx
                y += cy
            cx, cy, sx, sy = x, y, x, y
            pts.append((x, y))
            cmd = "l" if rel else "L"
        elif upper == "L":
            x, y = nums(2)
            if rel:
                x += cx
                y += cy
            cx, cy = x, y
            pts.append((x, y))
        elif upper == "H":
            (x,) = nums(1)
            if rel:
                x += cx
            cx = x
            pts.append((cx, cy))
        elif upper == "V":
            (y,) = nums(1)
            if rel:
                y += cy
            cy = y
            pts.append((cx, cy))
        elif upper in ("C", "S", "Q", "T"):
            count = {"C": 6, "S": 4, "Q": 4, "T": 2}[upper]
            vals = nums(count)
            if rel:
                vals = [v + (cx if k % 2 == 0 else cy) for k, v in enumerate(vals)]
            for k in range(0, count, 2):
                pts.append((vals[k], vals[k + 1]))
            cx, cy = vals[-2], vals[-1]
        elif upper == "A":
            x, y = arc_end()
            if rel:
                x += cx
                y += cy
            cx, cy = x, y
            pts.append((x, y))
        else:
            raise ValueError(f"unsupported command {cmd!r}")
    return pts


def normalized_viewbox(slugs: dict[str, list[str]]) -> str:
    """ViewBox that puts this view's figure at the shared fraction/margins/aspect."""
    pts = [p for ds in slugs.values() for d in ds for p in _path_points(d)]
    xs = [p[0] for p in pts]
    ys = [p[1] for p in pts]
    height = (max(ys) - min(ys)) / FIGURE_FRACTION
    width = height * ASPECT
    y_min = min(ys) - TOP_FRACTION * height
    x_min = (min(xs) + max(xs)) / 2 - width / 2
    return f"{x_min:.1f} {y_min:.1f} {width:.1f} {height:.1f}"


def svg(viewbox: str, body: str) -> str:
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{viewbox}">\n{body}</svg>\n'
    )


def paths(d_list: list[str], fill: str) -> str:
    return "".join(f'  <path d="{d}" fill="{fill}"/>\n' for d in d_list)


def base_svg(slugs: dict[str, list[str]], viewbox: str) -> str:
    body = ""
    for slug, ds in slugs.items():
        fill = STRUCT_FILL if slug in STRUCTURAL else MUSCLE_FILL
        body += paths(ds, fill)
    return svg(viewbox, body)


def write_set(out: Path, front: dict, back: dict, suffix: str) -> None:
    front_vb = normalized_viewbox(front)
    back_vb = normalized_viewbox(back)
    print(f"front{suffix}: {front_vb}   back{suffix}: {back_vb}")
    (out / f"front{suffix}.svg").write_text(base_svg(front, front_vb))
    (out / f"back{suffix}.svg").write_text(base_svg(back, back_vb))
    for wger_id, slug_list in FRONT_MAP.items():
        ds = [d for s in slug_list for d in front.get(s, [])]
        assert ds, f"front{suffix} slugs missing for wger id {wger_id}: {slug_list}"
        (out / f"muscle-{wger_id}{suffix}.svg").write_text(svg(front_vb, paths(ds, "#000")))
    for wger_id, slug_list in BACK_MAP.items():
        ds = [d for s in slug_list for d in back.get(s, [])]
        assert ds, f"back{suffix} slugs missing for wger id {wger_id}: {slug_list}"
        (out / f"muscle-{wger_id}{suffix}.svg").write_text(svg(back_vb, paths(ds, "#000")))


def main() -> None:
    out = OUT.resolve()
    out.mkdir(parents=True, exist_ok=True)

    # Wipe the previous asset set — filenames overlap but not fully.
    for old in out.glob("*.svg"):
        old.unlink()

    write_set(out, parse(HERE / "bodyFront.ts"), parse(HERE / "bodyBack.ts"), "")
    write_set(out, parse(HERE / "bodyFemaleFront.ts"), parse(HERE / "bodyFemaleBack.ts"), "-f")

    print(f"wrote {len(list(out.glob('*.svg')))} SVGs to {out}")


if __name__ == "__main__":
    main()
