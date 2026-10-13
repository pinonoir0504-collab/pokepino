#!/usr/bin/env python3
"""Validate the documented release catalog without mistaking historical candidates for products."""
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
file = root / "app/src/main/assets/official_catalog_2024_2026.json"
data = json.loads(file.read_text(encoding="utf-8"))
assert data["updated"] == "2026-10-13", "Unexpected catalog snapshot"
series = data["series"]
assert len(series) >= 14, "Too few documented releases"
total = 0
planned = 0
for release in series:
    assert release["source"].startswith("https://www.bandai.co.jp/candy/products/"), release
    assert release["releaseDate"].startswith(("2024", "2025", "2026")), release
    titles = release["items"]
    assert titles and len(titles) == len(set(titles)), release["series"]
    assert all(isinstance(t, str) and t.strip() for t in titles)
    total += len(titles)
    if release.get("planned"):
        planned += len(titles)
assert total == 184, total
assert planned == 24, planned
html = (root / "app/src/main/assets/collection.html").read_text(encoding="utf-8")
for feature in ("getCatalog", "getOwned", "changeCount", "searchMatch", "officialLink", "setFilter"):
    assert feature in html, feature
print(f"Official checked catalog: {total} entries / {len(series)} series (upcoming {planned}); HTML feature markers OK")
