#!/usr/bin/env python3
"""Evidence-first cross-source gap report, without certifying an incomplete collection."""
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path
from urllib.parse import urlparse

ROOT=Path(__file__).resolve().parents[1]
ASSETS=ROOT/"app/src/main/assets"
INDIR=Path(sys.argv[1] if len(sys.argv)>1 else "ten_pc_results")
OUT=Path(sys.argv[2] if len(sys.argv)>2 else "ten_pc_audit")
OUT.mkdir(parents=True,exist_ok=True)
old=json.loads((ASSETS/"historical_releases_1996_2023.json").read_text())["series"]
new=json.loads((ASSETS/"official_catalog_2024_2026.json").read_text())["series"]
workers=[]
for n in range(10):
    path=INDIR/f"worker_{n}.json"
    if not path.exists():
        raise SystemExit(f"Worker {n} artifact missing; cannot claim ten machines completed")
    workers.append(json.loads(path.read_text()))
existing=old+new
existing_urls={v["source"] for v in existing}
existing_names={re.sub(r"[\s\u3000]+","",v["series"].lower()) for v in existing}
cutoff="2026-10-01"
all_series=[]
for w in workers:
    for s in w["series"]:
        date=s.get("releaseDate","")
        if date and date[:4].isdigit() and date>cutoff:continue
        all_series.append(dict(s,worker=w["worker"]))
discovered_by_url=defaultdict(list)
for s in all_series:
    discovered_by_url[s["source"]].append(s)
news=[]
for url, sources in discovered_by_url.items():
    if url in existing_urls:continue
    possible=sorted(sources,key=lambda x:(x.get("sourceGrade")=="official",x.get("confidence")=="count_match"),reverse=True)[0]
    if possible.get("sourceGrade")=="official":
        news.append(possible)
news.sort(key=lambda x:(x["releaseDate"],x["series"]))
known_date_keys={(v["releaseDate"][:4],re.sub(r"[\s\u3000]+","",v["series"].lower())) for v in existing}
for s in news:
    s["possibleDuplicate"]= (s["releaseDate"][:4],re.sub(r"[\s\u3000]+","",s["series"].lower())) in known_date_keys
    s["needsManualReview"]=s.get("confidence")!="count_match" or s["possibleDuplicate"]
secondary=[]
for s in all_series:
    if s.get("sourceGrade")=="wiki_secondary":
        secondary.append(s)
candidates=[]
for w in workers:
    for c in w["candidates"]:
        if c.get("source") in existing_urls:continue
        candidates.append(dict(c,worker=w["worker"]))
dupe_candidates={}
for row in candidates:
    key=row.get("source") or row.get("series")
    dupe_candidates[key]=row
report={
    "cutoff":cutoff,"verificationClaim":"NOT COMPLETE: this is a gap audit, not proof of complete merchandise coverage",
    "machinesRequested":10,"machinesWithReports":len(workers),
    "workerResults":[{"id":w["worker"],"pages":len(w["pages"]),"extracted_series":len(w["series"]),
       "figure_rows":sum(len(s["items"]) for s in w["series"]),
       "candidates":len(w["candidates"]),"errors":len(w["errors"]),
       "errorsSample":w["errors"][:5]} for w in workers],
    "existingReleaseSeries":len(existing),
    "existingReleaseRows":sum(len(v["items"]) for v in existing),
    "foundOfficialMissingSeries":len(news),
    "foundOfficialMissingRows":sum(len(v["items"]) for v in news),
    "officialMissingCountMatched":sum(x.get("confidence")=="count_match" for x in news),
    "officialNeedingManualReview":sum(x["needsManualReview"] for x in news),
    "wikiSecondarySeries":len(secondary),
    "wikiSecondaryRows":sum(len(v["items"]) for v in secondary),
    "unreviewedLinks":len(dupe_candidates),
    "limits":["image views are not verified","wiki flags require manual matching to Japanese Bandai release IDs",
      "limited, lottery and campaign items may remain missing","historical records may repeat identical sculpts"]
}
(OUT/"audit_summary.json").write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n")
(OUT/"official_missing_series.json").write_text(json.dumps(news,ensure_ascii=False,indent=2)+"\n")
(OUT/"secondary_figures.json").write_text(json.dumps(secondary,ensure_ascii=False,indent=2)+"\n")
(OUT/"leads.json").write_text(json.dumps(list(dupe_candidates.values()),ensure_ascii=False,indent=2)+"\n")
print(json.dumps({k:v for k,v in report.items() if k not in ("workerResults","limits")},ensure_ascii=False,indent=2))
