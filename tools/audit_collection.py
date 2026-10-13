#!/usr/bin/env python3
"""Triple audit for Pokepino release/image metadata. Static audit; not front-pose verification."""
import base64
import collections
import gzip
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
ASSETS=ROOT/"app/src/main/assets"
parts=["dbgz_00.txt","dbgz_01a.txt","dbgz_01b.txt","dbgz_02a.txt","dbgz_02b.txt","dbgz_03a.txt","dbgz_03b.txt"]
base=json.loads(gzip.decompress(base64.b64decode("".join((ASSETS/n).read_text() for n in parts))))
references=json.loads((ASSETS/"catalog_v2.json").read_text())
official=json.loads((ASSETS/"official_catalog_2024_2026.json").read_text())
galleries=json.loads((ASSETS/"official_galleries_2024_2026.json").read_text())["series"]
all_official=[(s, name) for s in official["series"] for name in s["items"]]
new_references=[r for r in references if r[4]!="ポケモンキッズ一覧"]
def meaningful_photo(s):
    return s.startswith("https://") and not any(k in s.lower() for k in ("noimage", "placeholder", "pokeapi"))

# (1) release lineup and series inventory
assert len(official["series"])==14
assert len(all_official)==184
assert sum(len(s["items"]) for s in official["series"] if s["planned"])==24
assert all(s["source"] in galleries for s in official["series"])
assert all(len(s["items"])==len(set(s["items"])) for s in official["series"])
assert all(s["source"].startswith("https://www.bandai.co.jp/candy/products/") for s in official["series"])

# (2) photos, gaps, placeholders, source collisions. Orientation remains 'unknown'.
picture_entries=[r for r in new_references if r[8] and meaningful_photo(r[8][0])]
placeholder_entries=[r for r in new_references if r[8] and not meaningful_photo(r[8][0])]
unpictured=[r for r in new_references if not r[8]]
gallery_links=[u for v in galleries.values() for u in v["images"]]
assert all(v["verifiedAngle"]=="unknown" for v in galleries.values())
assert len(gallery_links)>=100, "Official gallery URLs missing"
assert all(u.startswith("https://www.bandai.co.jp/candy/published/bnc_files/product/") for u in gallery_links)

# (3) collisions, incorrectly reused images and records with uncertain identifiers.
urls=collections.defaultdict(list)
for row in references:
    if row[8]: urls[row[8][0]].append(row)
cross_species=[u for u,rows in urls.items() if len({r[1] for r in rows if r[1]>0})>1]
id_counts=collections.Counter(r[0] for r in base+references)
duplicate_ids=[x for x,count in id_counts.items() if count>1]
legacy_no_photo=sum(not r[8] for r in base)
historical_year_blank=sum(not r[5] for r in new_references)
unknown_dex=sum(r[1]<=0 for r in new_references)
report={
    "auditDate":"2026-10-13",
    "scope":"Pokepino historical candidates + 14 Bandai 2024-2026 series",
    "check1_release":{
        "baseRecords":len(base),
        "referenceRecords":len(references),
        "specificReferenceRecords":len(new_references),
        "officialReleases":len(official["series"]),
        "officialLineupProducts":len(all_official),
        "notYetReleased":sum(len(s["items"]) for s in official["series"] if s["planned"]),
        "historicalMissingYear":historical_year_blank,
        "speciesUnresolved":unknown_dex
    },
    "check2_images":{
        "specificReferencePhotoLinks":len(picture_entries),
        "specificReferenceBlankOrPlaceholder":len(unpictured)+len(placeholder_entries),
        "placeholderRecords":len(placeholder_entries),
        "officialGalleryImageLinks":len(gallery_links),
        "officialReleaseGalleries":sum(bool(galleries[s["source"]]["images"]) for s in official["series"]),
        "originalLegacyMissingPhotos":legacy_no_photo,
        "frontFacingVerified":0,
        "frontVerificationNote":"Image angle and actual product correspondence have NOT been visually checked for all URLs"
    },
    "check3_collisions":{
        "sameUrlDifferentSpecies":len(cross_species),
        "suspiciousUrls":cross_species,
        "duplicateIds":duplicate_ids,
        "oldCandidatesNeedIndividualReleaseProof":sum(r[7]=="species_only" for r in base)
    }
}
out=ROOT/"collection_audit.json"
out.write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
print(json.dumps(report,ensure_ascii=False,indent=2))
