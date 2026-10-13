#!/usr/bin/env python3
"""Compare 10 crawlers, produce verified release galleries and conservative item-image gaps."""
import json, collections
from pathlib import Path
from crawl_official_images import sources
def main():
    root=Path("crawl-results")
    reports=[json.loads(p.read_text(encoding="utf-8")) for p in root.rglob("worker-*.json")]
    by_worker={r["worker"]:r for r in reports}
    confirmed={}; mismatches=[]; failures=[]
    for shard in range(5):
        a=by_worker.get(shard);b=by_worker.get(shard+5)
        if not a or not b:
            failures.append({"shard":shard,"reason":"missing independent worker"})
            continue
        aa={r["source"]:r for r in a["results"]};bb={r["source"]:r for r in b["results"]}
        for url in sorted(set(aa)|set(bb)):
            x=aa.get(url);y=bb.get(url)
            if not x or not y or x["status"]!="ok" or y["status"]!="ok":
                failures.append({"source":url,"first":x,"second":y})
            elif x["images"]==y["images"]:
                confirmed[url]={"images":x["images"],"itemCount":x["itemCount"],"series":x["series"],
                                "evidence":"same image URL set observed by two independent crawlers"}
            else:
                mismatches.append({"source":url,"first":x["images"],"second":y["images"]})
    # Critical: agreement only verifies release gallery membership, not figure-specific identity.
    output={"independentWorkers":len(by_worker),"expectedWorkers":10,
            "confirmedReleaseGalleries":confirmed,"mismatches":mismatches,"failures":failures,
            "itemSpecificPhotosAutomaticallyAssigned":0,
            "note":"Do not map gallery images to individual figures without item-name/variant visual verification."}
    Path("crawl-consensus.json").write_text(json.dumps(output,ensure_ascii=False,indent=2),encoding="utf-8")
    print(json.dumps({"workers":len(by_worker),"releaseGalleries":len(confirmed),
                      "mismatches":len(mismatches),"failures":len(failures),"itemSpecificAssignments":0}))
if __name__=="__main__":main()
