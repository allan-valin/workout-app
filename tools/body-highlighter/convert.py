#!/usr/bin/env python3
"""Generate app/src/main/assets/body/ SVGs from react-native-body-highlighter path data.

Source: https://github.com/HichamELBSI/react-native-body-highlighter (MIT, see LICENSE here).
bodyFront.ts / bodyBack.ts are vendored verbatim next to this script.

Outputs (same filenames the app already loads — ui/common/BodyMap.kt):
  front.svg / back.svg              full body, neutral grays (structural vs muscle)
  muscle-<wgerId>.svg               the wger muscle's region(s), black fill (tinted at runtime)

The male front view uses viewBox "0 0 724 1448"; the back paths live at x+724, so the back
view uses viewBox "724 0 724 1448" (matches the library's SvgMaleWrapper).

Run from the repo root:  python3 tools/body-highlighter/convert.py
"""
import re
from pathlib import Path

HERE = Path(__file__).parent
OUT = HERE / "../../app/src/main/assets/body"

FRONT_VIEWBOX = "0 0 724 1448"
BACK_VIEWBOX = "724 0 724 1448"

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
        for path in re.findall(r'"(M[^"]+)"', line):
            if current is not None:
                slugs[current].append(path)
    return slugs


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


def main() -> None:
    front = parse(HERE / "bodyFront.ts")
    back = parse(HERE / "bodyBack.ts")
    out = OUT.resolve()
    out.mkdir(parents=True, exist_ok=True)

    # Wipe the previous (wger) asset set — filenames overlap but not fully.
    for old in out.glob("*.svg"):
        old.unlink()

    (out / "front.svg").write_text(base_svg(front, FRONT_VIEWBOX))
    (out / "back.svg").write_text(base_svg(back, BACK_VIEWBOX))

    for wger_id, slug_list in FRONT_MAP.items():
        ds = [d for s in slug_list for d in front.get(s, [])]
        assert ds, f"front slugs missing for wger id {wger_id}: {slug_list}"
        (out / f"muscle-{wger_id}.svg").write_text(svg(FRONT_VIEWBOX, paths(ds, "#000")))
    for wger_id, slug_list in BACK_MAP.items():
        ds = [d for s in slug_list for d in back.get(s, [])]
        assert ds, f"back slugs missing for wger id {wger_id}: {slug_list}"
        (out / f"muscle-{wger_id}.svg").write_text(svg(BACK_VIEWBOX, paths(ds, "#000")))

    print(f"wrote {len(list(out.glob('*.svg')))} SVGs to {out}")


if __name__ == "__main__":
    main()
