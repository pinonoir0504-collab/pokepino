#!/usr/bin/env python3
import base64, gzip, json, sys
from collections import defaultdict
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
ASSETS=ROOT/"app"/"src"/"main"/"assets"
PARTS=["dbgz_00.txt","dbgz_01a.txt","dbgz_01b.txt","dbgz_02a.txt","dbgz_02b.txt","dbgz_03a.txt","dbgz_03b.txt"]

def fail(msg):
    print("ERROR:",msg,file=sys.stderr)
    raise SystemExit(2)

raw="".join((ASSETS/p).read_text(encoding="utf-8") for p in PARTS)
try:
    existing=json.loads(gzip.decompress(base64.b64decode(raw)).decode("utf-8"))
except Exception as e:
    fail(f"compressed base catalog is invalid: {e}")

cat_path=ASSETS/"catalog_v2.json"
if not cat_path.exists():
    fail("catalog_v2.json is missing")
try:
    catalog=json.loads(cat_path.read_text(encoding="utf-8"))
except Exception as e:
    fail(f"catalog_v2.json is invalid: {e}")

if len(existing)<1300:
    fail(f"base catalog too small: {len(existing)}")
if len(catalog)<1000:
    fail(f"reference catalog too small: {len(catalog)}")

ids=set()
species=set()
features=0
groups=defaultdict(set)
bad_features=[]
for i,row in enumerate(catalog):
    if not isinstance(row,list) or len(row)<10:
        fail(f"bad catalog row {i}")
    rid=str(row[0])
    if not rid or rid in ids:
        fail(f"duplicate/blank id: {rid}")
    ids.add(rid)
    dex=int(row[1])
    if dex<0 or dex>1200:
        fail(f"invalid dex {dex} for {rid}")
    if dex>0:
        species.add(dex)
    group=str(row[6])
    groups[group].add(dex)
    meta=row[9] if isinstance(row[9],dict) else {}
    feat=meta.get("feature","")
    if feat:
        features+=1
        try:
            decoded=base64.b64decode(feat,validate=True)
            if len(decoded)!=264:
                bad_features.append((rid,len(decoded)))
        except Exception:
            bad_features.append((rid,-1))

if len(species)<800:
    fail(f"reference species coverage too small: {len(species)}")
if features<1000:
    fail(f"too few usable visual signatures: {features}")
if bad_features:
    fail(f"invalid visual signatures: {bad_features[:5]}")
bad_groups=[(g,sorted(d)) for g,d in groups.items() if len({x for x in d if x>0})>1]
if bad_groups:
    fail(f"visual group crosses species: {bad_groups[:5]}")

print(json.dumps({
    "base_records":len(existing),
    "base_species":len({int(r[1]) for r in existing if int(r[1])>0}),
    "reference_records":len(catalog),
    "reference_species":len(species),
    "visual_signatures":features,
    "visual_groups":len(groups),
},ensure_ascii=False,indent=2))


embed_path=ASSETS/"embedding_catalog_v3.json"
model_path=ASSETS/"mobilenet_v3_small_features.onnx"
recognizer_source=ROOT/"app"/"src"/"main"/"java"/"com"/"pokepino"/"app"/"EmbeddingRecognizer.kt"
if recognizer_source.exists():
    if not embed_path.exists():
        fail("embedding_catalog_v3.json is missing")
    if not model_path.exists() or model_path.stat().st_size<2_000_000:
        fail("MobileNet embedding model is missing or too small")
    try:
        embeddings=json.loads(embed_path.read_text(encoding="utf-8"))
    except Exception as e:
        fail(f"embedding catalog is invalid: {e}")
    if len(embeddings)<3500:
        fail(f"embedding catalog too small: {len(embeddings)}")
    embed_species=set()
    bad_embeddings=[]
    for i,row in enumerate(embeddings):
        if not isinstance(row,list) or len(row)<4:
            bad_embeddings.append((i,"shape"))
            continue
        dex=int(row[1])
        if dex>0:
            embed_species.add(dex)
        try:
            raw=base64.b64decode(str(row[3]),validate=True)
            if len(raw)!=576:
                bad_embeddings.append((i,len(raw)))
        except Exception:
            bad_embeddings.append((i,-1))
    if len(embed_species)<800:
        fail(f"embedding species coverage too small: {len(embed_species)}")
    if bad_embeddings:
        fail(f"invalid embedding rows: {bad_embeddings[:5]}")
    print(json.dumps({
        "embedding_rows":len(embeddings),
        "embedding_species":len(embed_species),
        "embedding_model_bytes":model_path.stat().st_size,
    },ensure_ascii=False,indent=2))

supported_species={int(r[1]) for r in existing+catalog if 1<=int(r[1])<=1025}
photo_path=ASSETS/"species_photo_refs_v1.json"
if photo_path.exists():
    manifest=json.loads((ROOT/"species_photo_refs_manifest.json").read_text(encoding="utf-8"))
    photos=json.loads(photo_path.read_text(encoding="utf-8"))
    sources={s["item_id"]:s for s in manifest["sources"]}
    if manifest.get("dimensions")!=576 or manifest.get("penalty")!=0.05:
        fail("species-only prototype schema does not match recognizer")
    if len(sources)!=manifest.get("training_source_count") or len(photos)!=len(sources)*4:
        fail("species photo prototype count does not match provenance")
    seen_photo_ids=set()
    for row in photos:
        if len(row)!=5 or row[0] in seen_photo_ids or row[2]!="" or int(row[1]) not in supported_species:
            fail("invalid species-only prototype identity or variant group")
        seen_photo_ids.add(row[0])
        source_id,augmentation=row[0].rsplit("-",1)
        source=sources.get(source_id)
        if source is None or source["dex"]!=row[1] or source.get("species_only") is not True:
            fail("species photo prototype has no matching verified source")
        if augmentation not in {"base","mirror","dark","bright"} or row[4]!=augmentation:
            fail("invalid species photo augmentation")
        raw=base64.b64decode(row[3],validate=True)
        if len(raw)!=576 or not any(raw):
            fail("invalid species photo vector")
    print(json.dumps({"species_photo_sources":len(sources),"species_photo_vectors":len(photos)},indent=2))
