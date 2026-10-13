#!/usr/bin/env python3
import json
from pathlib import Path
a=Path(__file__).resolve().parents[1]/"app/src/main/assets"
history=json.loads((a/"historical_releases_1996_2023.json").read_text(encoding="utf-8"))["series"]
galleries=json.loads((a/"historical_official_galleries.json").read_text(encoding="utf-8"))["series"]
official=json.loads((a/"official_catalog_2024_2026.json").read_text(encoding="utf-8"))["series"]
assert len(history)==29, len(history)
assert sum(len(s["items"]) for s in history)==440
assert len(official)==14 and sum(len(s["items"]) for s in official)==184
assert len(galleries)==12
urls=sum(len(s["images"]) for s in galleries.values())
assert urls>=175,urls
keys=set()
for s in history:
    key=(s["releaseDate"],s["series"])
    assert key not in keys,key
    keys.add(key)
    assert s["source"].startswith("https://")
    assert s["sourceGrade"] in {"official","collector","official_count_collector_names"}
    assert s["items"] and len(s["items"])==len(set(s["items"]))
    assert all(isinstance(v,str) and v.strip() for v in s["items"])
release={s["series"]:s for s in history}
assert "ミュウ（シークレット）" in release["2018 ポケモンキッズ 初代復刻弾"]["items"]
assert "ヤバチャ（しんさくフォルム・シークレット）" in release["2022 ポケモンキッズ 旅のなかまたち編"]["items"]
assert len(release["1996 ポケモンキッズ 初弾（8種）"]["items"])==8
assert len(release["2008 ポケモンキッズDP ぜんこく図鑑版"]["items"])==15
for p,entry in galleries.items():
    assert p.startswith("https://www.bandai.co.jp/candy/products/")
    assert len(entry["images"])>=5
    assert all(v.startswith("https://www.bandai.co.jp/candy/published/bnc_files/product/") for v in entry["images"])
print(json.dumps({"historical_series":29,"historical_entries":440,"modern_entries":184,"extra_gallery_images":urls},ensure_ascii=False,indent=2))
