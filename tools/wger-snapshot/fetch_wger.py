#!/usr/bin/env python3
"""Fetch a wger exercise-database snapshot and emit the app's bundled asset.

Output: app/src/main/assets/wger_snapshot.json
Only stdlib is used (urllib), so it runs on a bare python3.

Safety: HTTPS to wger.de only, strict pagination bound, response size sanity
checks, plain-text extraction of descriptions (HTML stripped).
"""

import json
import re
import sys
import time
import urllib.request
from pathlib import Path

BASE = "https://wger.de/api/v2"
OUT = Path(__file__).resolve().parents[2] / "app/src/main/assets/wger_snapshot.json"
MAX_PAGES = 200  # hard bound; ~1000 exercises at limit=100 needs ~10
WANTED_LANGS = {"en", "de", "pt"}  # wger uses generic pt for Brazilian Portuguese content
TAG_RE = re.compile(r"<[^>]+>")


def get(url: str):
    req = urllib.request.Request(url, headers={"User-Agent": "workout-app-snapshot/1.0"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        data = resp.read()
        if len(data) > 20_000_000:
            raise RuntimeError(f"response too large: {url}")
        return json.loads(data)


def get_paginated(path: str):
    results = []
    url = f"{BASE}/{path}"
    for _ in range(MAX_PAGES):
        page = get(url)
        results.extend(page["results"])
        url = page.get("next")
        if not url:
            return results
        if not url.startswith("https://wger.de/"):
            raise RuntimeError(f"refusing non-wger pagination url: {url}")
        time.sleep(0.3)  # be polite
    raise RuntimeError(f"pagination bound exceeded for {path}")


def strip_html(text: str) -> str:
    return TAG_RE.sub("", text or "").replace("&nbsp;", " ").strip()


def main():
    langs = {l["id"]: l["short_name"] for l in get_paginated("language/?limit=100")}
    wanted_ids = {i for i, s in langs.items() if s in WANTED_LANGS}
    print(f"languages: { {i: langs[i] for i in wanted_ids} }")

    muscles = [
        {"id": m["id"], "name": m["name"], "name_en": m.get("name_en") or m["name"],
         "is_front": m.get("is_front", True)}
        for m in get_paginated("muscle/?limit=100")
    ]
    equipment = [{"id": e["id"], "name": e["name"]} for e in get_paginated("equipment/?limit=100")]

    raw = get_paginated("exerciseinfo/?limit=100")
    print(f"exercise bases fetched: {len(raw)}")

    exercises = []
    for ex in raw:
        translations = {}
        for tr in ex.get("translations", []):
            short = langs.get(tr.get("language"))
            if short not in WANTED_LANGS:
                continue
            name = (tr.get("name") or "").strip()
            if not name:
                continue
            entry = {
                "name": name,
                "description": strip_html(tr.get("description", ""))[:2000],
                "aliases": sorted({a["alias"].strip() for a in tr.get("aliases", []) if a.get("alias", "").strip()}),
            }
            # Keep the first translation per language (wger occasionally has duplicates).
            translations.setdefault(short, entry)
        if "en" not in translations:
            continue  # unnamed-in-English entries are junk data
        images = [i["image"] for i in ex.get("images", []) if i.get("image", "").startswith("https://wger.de/")]
        main_img = next((i["image"] for i in ex.get("images", []) if i.get("is_main")), images[0] if images else None)
        exercises.append({
            "id": ex["id"],
            "category": (ex.get("category") or {}).get("name"),
            "muscles": [m["id"] for m in ex.get("muscles", [])],
            "muscles_secondary": [m["id"] for m in ex.get("muscles_secondary", [])],
            "equipment": [e["id"] for e in ex.get("equipment", [])],
            "image": main_img,
            "license_author": ", ".join(ex.get("authors", [])) if isinstance(ex.get("authors"), list) else None,
            "translations": translations,
        })

    snapshot = {
        "schema": 1,
        "source": "wger.de (CC-BY-SA 4.0)",
        "muscles": muscles,
        "equipment": equipment,
        "exercises": exercises,
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(snapshot, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    per_lang = {l: sum(1 for e in exercises if l in e["translations"]) for l in WANTED_LANGS}
    print(f"wrote {OUT} ({OUT.stat().st_size // 1024} KiB), exercises={len(exercises)}, per-lang={per_lang}")


if __name__ == "__main__":
    sys.exit(main())
