#!/usr/bin/env python3
"""Official Bandai product page crawl, 10 independent runners / 5 overlapping shards.
Only verify that image URLs belong to a release; NEVER infer a figure's identity from gallery order.
"""
import argparse, hashlib, html, json, re, time, urllib.request
from pathlib import Path
from urllib.parse import urljoin, urlparse

ROOT=Path(__file__).resolve().parents[1]
ASSETS=ROOT/"app/src/main/assets"
def load(path):
    return json.loads((ASSETS/path).read_text(encoding="utf-8"))
def sources():
    sets=[load("official_catalog_2024_2026.json"),load("audit_verified_official_2014_2022.json"),load("historical_releases_1996_2023.json")]
    entries={}
    for data in sets:
        for s in data["series"]:
            url=s.get("source","")
            if url.startswith("https://www.bandai.co.jp/candy/products/") and url.endswith(".html"):
                entries.setdefault(url,{"source":url,"series":s["series"],"items":s["items"]})
    return [entries[u] for u in sorted(entries)]
def crawl(row):
    url=row["source"]
    req=urllib.request.Request(url,headers={"User-Agent":"PokepinoCatalogAudit/1.0 (+public product metadata)"})
    try:
        with urllib.request.urlopen(req,timeout=22) as res:
            body=res.read(2_000_000).decode("utf-8","replace")
            final=res.geturl()
        if urlparse(final).hostname!="www.bandai.co.jp":
            raise ValueError("unexpected redirect")
        # Both direct src and lazy-loaded gallery attributes appear in Bandai product HTML.
        raw=re.findall(r'(?:https?:)?//(?:www\\.)?bandai\\.co\\.jp/candy/published/bnc_files/product/[^"\\\'<>\\s]+?\\.(?:jpg|jpeg|png|webp)',html.unescape(body),flags=re.I)
        raw += re.findall(r'(?<![a-z])(/candy/published/bnc_files/product/[^"\\\'<>\\s]+?\\.(?:jpg|jpeg|png|webp))',html.unescape(body),flags=re.I)
        images=sorted(set(urljoin(url,x) for x in raw))
        return {"source":url,"series":row["series"],"itemCount":len(row["items"]),
                "images":images,"pageSha256":hashlib.sha256(body.encode()).hexdigest(),
                "status":"ok" if images else "no-gallery-images"}
    except Exception as exc:
        return {"source":url,"series":row["series"],"itemCount":len(row["items"]),
                "images":[],"status":"error","error":str(exc)[:250]}
def main():
    p=argparse.ArgumentParser()
    p.add_argument("--worker",type=int,required=True,choices=range(10))
    p.add_argument("--out",required=True)
    args=p.parse_args()
    # 5 shards; each shard independently crawled by two distinct runners.
    selected=[r for i,r in enumerate(sources()) if i%5==args.worker%5]
    results=[]
    for row in selected:
        results.append(crawl(row))
        time.sleep(.4)
    dest=Path(args.out);dest.parent.mkdir(parents=True,exist_ok=True)
    dest.write_text(json.dumps({"worker":args.worker,"shard":args.worker%5,"results":results},ensure_ascii=False,indent=2),encoding="utf-8")
    print("worker",args.worker,"pages",len(results),"with-gallery",sum(x["status"]=="ok" for x in results))
if __name__=="__main__":main()
