#!/usr/bin/env python3
"""Map wger exercise ids to free-exercise-db (yuhonas) slugs by English name.

Conservative: exact normalized-name match, then alias match, then token-set
equality. Ambiguous or weak candidates are dropped — a wrong movement animation
is worse than none. Output: app/src/main/assets/fed_map.json {"wger:ID": slug}.

Usage: python3 tools/fed-map/generate.py  (fetches the fed dataset if missing)
"""
import json
import re
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SNAPSHOT = ROOT / "app/src/main/assets/wger_snapshot.json"
OUT = ROOT / "app/src/main/assets/fed_map.json"
FED_URL = "https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/dist/exercises.json"
FED_CACHE = Path(__file__).with_name("exercises.json")

STOP = {"the", "a", "an", "with", "of", "on", "to", "and", "or"}
SYNONYMS = {  # one canonical token per common phrasing difference
    "olympic": "barbell",
    "bb": "barbell",
    "db": "dumbbell",
    "dumbbells": "dumbbell",
    "smith": "machine",
    "seated": "sitting",
    "bentover": "bent",
    "bent-over": "bent",
    "overhead": "shoulder",
}


def norm_tokens(name: str) -> frozenset:
    name = name.lower().replace("-", " ").replace("/", " ")
    toks = re.findall(r"[a-z0-9]+", name)
    out = set()
    for t in toks:
        t = SYNONYMS.get(t, t)
        if t in STOP:
            continue
        out.add(t[:-1] if t.endswith("s") and len(t) > 3 else t)  # crude singular
    return frozenset(out)


def main():
    if not FED_CACHE.exists():
        urllib.request.urlretrieve(FED_URL, FED_CACHE)
    fed = json.loads(FED_CACHE.read_text())
    wger = json.loads(SNAPSHOT.read_text())["exercises"]

    fed_by_tokens = {}
    for f in fed:
        fed_by_tokens.setdefault(norm_tokens(f["name"]), []).append(f["id"])

    mapping = {}
    ambiguous = 0
    for ex in wger:
        en = ex.get("translations", {}).get("en")
        if not en:
            continue
        names = [en["name"]] + list(en.get("aliases") or [])
        hit = None
        for name in names:
            slugs = fed_by_tokens.get(norm_tokens(name))
            if slugs and len(slugs) == 1:
                hit = slugs[0]
                break
            if slugs and len(slugs) > 1:
                ambiguous += 1
        if hit:
            mapping[f"wger:{ex['id']}"] = hit

    OUT.write_text(json.dumps(mapping, indent=0, sort_keys=True))
    print(f"mapped {len(mapping)}/{len(wger)} (ambiguous name collisions skipped: {ambiguous})")


if __name__ == "__main__":
    main()
