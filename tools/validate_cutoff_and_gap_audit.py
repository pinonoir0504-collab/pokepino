#!/usr/bin/env python3
"""Independent metadata constraints for the cutoff inventory; this never certifies full coverage."""
import json
from pathlib import Path
p=Path(__file__).resolve().parents[1]/"app/src/main/assets"
new=json.loads((p/"audit_verified_official_2014_2022.json").read_text())
old=json.loads((p/"historical_releases_1996_2023.json").read_text())
future=json.loads((p/"official_catalog_2024_2026.json").read_text())
secondary=json.loads((p/"secondary_worldwide_release_audit.json").read_text())
assert new["cutoff"]=="2026-10-01"
assert len(new["series"])==16
assert sum(len(s["items"]) for s in new["series"])==182
assert len(secondary["series"])>=129
assert sum(bool(s["items"]) for s in secondary["series"])>=70
assert all(s["sourceGrade"]=="secondary_wiki_unverified" for s in secondary["series"])
assert all(s["releaseDate"]<="2026-10-01" and s["source"].startswith("https://www.bandai.co.jp/candy/products/") for s in new["series"])
assert all(s["expected"]==len(s["items"]) and s["expected"]>=6 for s in new["series"])
assert any("サトシのメルタン" in it for s in new["series"] for it in s["items"])
assert any("ミュウツーの逆襲" in s["series"] for s in new["series"])
assert len(old["series"])==29
assert len(future["series"])==14
planned=[s for s in future["series"] if s.get("planned") or s["releaseDate"]>"2026-10-01"]
assert len(planned)==2 and sum(len(s["items"]) for s in planned)==24
kotlin=(p.parent/"java/com/pokepino/app/CollectionActivity.kt").read_text()
assert 'year > "2026-10-01"' in kotlin
assert 'date > "2026-10-01"' in kotlin
assert "audit_verified_official_2014_2022.json" in kotlin
print("PASS: 16 official groups / 182 figures and 129 secondary research groups; future 24 excluded")
