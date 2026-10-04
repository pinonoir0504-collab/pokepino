#!/usr/bin/env python3
import argparse, base64, csv, gzip, hashlib, io, json, re, sys, time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from urllib.parse import urljoin, urlparse, urldefrag

import numpy as np
import requests
from bs4 import BeautifulSoup
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app" / "src" / "main" / "assets"
OUT = ASSETS / "catalog_v2.json"
STATS = ROOT / "catalog_stats.json"
UA = "PokepinoCatalogBuilder/1.0 (+personal collection index; polite crawler)"
START_URLS = [
    "https://www.yubi-nin.jp/pokemon/_pokeALL/pokemon_ALL00.html",
    "https://www.yubi-nin.jp/pokemon/poke_fukkoku/poke_THE.html",
    "https://www.yubi-nin.jp/pokemon/poke_fukkoku/poke_Fukoku01.html",
    "https://www.yubi-nin.jp/pokemon/poke_kime01/poke_kime3.html",
    "https://www.yubi-nin.jp/pokemon/poke_kime01/poke_kime4.html",
    "https://www.yubi-nin.jp/pokemon/poke_kime01/poke_kime5.html",
    "https://www.yubi-nin.jp/pokemon/poke_kime02DP/poke_kimeDP.html",
    "https://www.yubi-nin.jp/pokemon/poke_kime02DP/poke_kimeDP2.html",
    "https://www.yubi-nin.jp/pokemon/poke_kime03BW/poke_kimeBW01.html",
    "https://www.yubi-nin.jp/pokemon/poke_kime04XY/poke_kimeXY01.html",
    "https://www.yubi-nin.jp/pokemon/poke_kids10SS/poke_SS01.html",
    "https://www.yubi-nin.jp/pokemon/poke_kids10SS/poke_SS03.html",
    "https://www.yubi-nin.jp/pokemon/poke_kids11SV/poke_SV01.html",
    "https://www.yubi-nin.jp/pokemon/poke_movie/PokeM.html",
    "https://www.yubi-nin.jp/pokemon/poke_set/KidsDX.html",
    "https://www.yubi-nin.jp/pokemon/poke_30th/poke_30th01.html",
]
NAME_CSV = "https://raw.githubusercontent.com/PokeAPI/pokeapi/master/data/v2/csv/pokemon_species_names.csv"
SPECIAL = ("クリア","色違","アローラ","ガラル","ヒスイ","パルデア","メガ","キョダイ","ゲンシ","テラスタル","なみのり","おきがえ","サトシ","キャプテン")
TRAINERS = ("サトシ","ゴウ","シロナ","リコ","ロイ","フリード")

session = requests.Session()
session.headers.update({"User-Agent": UA, "Accept-Language":"ja,en;q=0.7"})

def get(url, timeout=25):
    last = None
    for n in range(3):
        try:
            r = session.get(url, timeout=timeout)
            if r.status_code == 200:
                r.encoding = r.apparent_encoding or r.encoding
                return r
            last = RuntimeError(f"HTTP {r.status_code} {url}")
        except Exception as e:
            last = e
        time.sleep(0.5 * (n + 1))
    raise last or RuntimeError(url)

def norm(s):
    return re.sub(r"[\s　・･_\-—–:：/／,.。'\"（）()\[\]【】]+", "", (s or "")).lower()

def load_existing():
    parts = [
        "dbgz_00.txt","dbgz_01a.txt","dbgz_01b.txt",
        "dbgz_02a.txt","dbgz_02b.txt","dbgz_03a.txt","dbgz_03b.txt"
    ]
    raw = "".join((ASSETS / p).read_text(encoding="utf-8") for p in parts)
    return json.loads(gzip.decompress(base64.b64decode(raw)).decode("utf-8"))

def load_species_names():
    text = get(NAME_CSV).text
    names = {}
    for row in csv.DictReader(io.StringIO(text)):
        if row.get("local_language_id") in {"1","11"}:
            name = row.get("name","").strip()
            if name:
                names[norm(name)] = (int(row["pokemon_species_id"]), name)
    aliases = {
        "ニドランオス":(32,"ニドラン♂"), "ニドランメス":(29,"ニドラン♀"),
        "ニドラン♂":(32,"ニドラン♂"), "ニドラン♀":(29,"ニドラン♀"),
        "ケンタウロス":(128,"ケンタロス"), "デイグダ":(50,"ディグダ"),
    }
    for k,v in aliases.items():
        names[norm(k)] = v
    return names

def resolve_species(text, species_names):
    n = norm(text)
    found = []
    for key, pair in species_names.items():
        p = n.find(key)
        if p >= 0:
            found.append((p, -len(key), pair))
    if not found:
        return (0, "")
    found.sort()
    return found[0][2]

def page_title(soup, url):
    h = soup.find(["h1","h2"])
    if h:
        t = h.get_text(" ", strip=True)
        if "ポケモン" in t:
            return t
    t = soup.title.get_text(" ", strip=True) if soup.title else Path(urlparse(url).path).stem
    return re.sub(r"\s*[｜|].*$", "", t).strip()

def page_year(text):
    m = re.search(r"発売年\s*[|｜:]?\s*(20\d{2}|19\d{2})", text)
    if not m:
        m = re.search(r"(20\d{2}|19\d{2})年", text)
    return m.group(1) if m else ""

def image_feature(data):
    try:
        im = Image.open(io.BytesIO(data)).convert("RGB")
        arr = np.asarray(im, dtype=np.float32)
        h,w,_ = arr.shape
        if h < 12 or w < 12:
            return None
        border = np.concatenate([arr[0,:,:],arr[-1,:,:],arr[:,0,:],arr[:,-1,:]], axis=0)
        bg = np.median(border, axis=0)
        dist = np.linalg.norm(arr-bg, axis=2)
        mask = dist > max(28.0, float(np.percentile(dist, 58)))
        ys,xs = np.where(mask)
        if len(xs) > max(20, h*w*0.015):
            x0,x1 = max(0,int(xs.min())-2), min(w,int(xs.max())+3)
            y0,y1 = max(0,int(ys.min())-2), min(h,int(ys.max())+3)
            im = im.crop((x0,y0,x1,y1))
        im = im.resize((32,32), Image.Resampling.LANCZOS)
        a = np.asarray(im, dtype=np.float32)
        blocks = a.reshape(8,4,8,4,3).mean(axis=(1,3)).clip(0,255).astype(np.uint8).reshape(-1)
        hsv = np.asarray(im.convert("HSV"), dtype=np.uint8)
        hist = []
        for channel,bins in [(0,24),(1,8),(2,8)]:
            x,_ = np.histogram(hsv[:,:,channel], bins=bins, range=(0,256))
            x = (255*x/max(1,x.max())).astype(np.uint8)
            hist.extend(x.tolist())
        gray = np.asarray(im.convert("L"), dtype=np.int16)
        edge = np.zeros((16,16), dtype=np.uint8)
        small = np.asarray(im.resize((16,16), Image.Resampling.BILINEAR).convert("L"), dtype=np.int16)
        gx = np.abs(np.diff(small,axis=1,prepend=small[:,:1]))
        gy = np.abs(np.diff(small,axis=0,prepend=small[:1,:]))
        edge[(gx+gy)>38]=1
        packed = np.packbits(edge.reshape(-1))
        payload = bytes(blocks.tolist()+hist) + packed.tobytes()
        return base64.b64encode(payload).decode("ascii")
    except Exception:
        return None

def candidate_links(soup, base):
    out = set()
    for a in soup.find_all("a", href=True):
        u = urldefrag(urljoin(base, a["href"]))[0]
        p = urlparse(u)
        if p.scheme not in {"http","https"}:
            continue
        if p.netloc.lower().replace("www.","") != "yubi-nin.jp":
            continue
        if not p.path.startswith("/pokemon/") or not p.path.lower().endswith((".html",".htm")):
            continue
        out.add(u.replace("http://","https://"))
    return out

def parse_page(url, html, species_names):
    soup = BeautifulSoup(html, "html.parser")
    text = soup.get_text(" ", strip=True)
    title = page_title(soup,url)
    year = page_year(text)
    relevant = ("ポケモンキッズ" in text or "キメわざポケモン" in text or "pokemon kids" in text.lower())
    records = []
    if relevant:
        for img in soup.find_all("img"):
            src = img.get("src") or img.get("data-src") or img.get("data-original")
            if not src:
                continue
            alt = " ".join(filter(None,[img.get("alt",""),img.get("title","")])).strip()
            parent_text = img.parent.get_text(" ", strip=True)[:220] if img.parent else ""
            ctx = (alt + " " + parent_text).strip()
            if not ctx:
                continue
            dex,pokemon = resolve_species(ctx, species_names)
            is_trainer = any(t in ctx for t in TRAINERS)
            nums = re.findall(r"No[.\s]*0*(\d{1,4})", ctx, flags=re.I)
            if dex == 0 and not is_trainer:
                continue
            if not nums and dex and len(alt) < 2:
                continue
            img_url = urljoin(url,src)
            kids_no = int(nums[0]) if nums else 0
            appearance = []
            for token in SPECIAL:
                if token in ctx or token in title:
                    appearance.append(token)
            move = re.search(r"《([^》]+)》", ctx)
            if move:
                appearance.append("技:"+move.group(1))
            if "キメわざ" in title:
                appearance.append("キメわざ")
            app_key = "|".join(sorted(set(appearance))) or "normal"
            group = f"KIDS-{kids_no:04d}-{hashlib.sha1(app_key.encode()).hexdigest()[:8]}" if kids_no else f"OTHER-{hashlib.sha1(ctx.encode()).hexdigest()[:10]}"
            rid = "YBN-" + hashlib.sha1((url+"|"+img_url+"|"+ctx).encode()).hexdigest()[:14]
            variant = re.sub(r"\s+"," ",ctx).strip()
            records.append({
                "id":rid, "dex":dex, "pokemon":pokemon or next((t for t in TRAINERS if t in ctx),""),
                "variant":variant[:180], "series":title[:140], "year":year,
                "visualGroupId":group, "status":"reference_pending",
                "refs":[img_url], "kidsNo":kids_no, "source":url, "feature":"",
            })
    return soup, records

def crawl(species_names, max_pages=1200, workers=8):
    queue = list(dict.fromkeys(START_URLS))
    seen = set()
    pages = {}
    records = []
    while queue and len(seen) < max_pages:
        batch=[]
        while queue and len(batch)<workers and len(seen)+len(batch)<max_pages:
            u=queue.pop(0)
            if u in seen or u in batch:
                continue
            batch.append(u)
        if not batch:
            continue
        with ThreadPoolExecutor(max_workers=workers) as ex:
            fut={ex.submit(get,u):u for u in batch}
            for f in as_completed(fut):
                u=fut[f]
                seen.add(u)
                try:
                    r=f.result()
                    soup,recs=parse_page(u,r.text,species_names)
                    pages[u]=len(recs)
                    records.extend(recs)
                    for link in candidate_links(soup,u):
                        if link not in seen and link not in queue:
                            queue.append(link)
                except Exception:
                    pages[u]=-1
    return records,pages

def attach_features(records, max_images=2500, workers=8):
    unique=[]
    seen=set()
    for r in records:
        u=r["refs"][0]
        if u not in seen:
            seen.add(u); unique.append(u)
    unique=unique[:max_images]
    fmap={}
    def one(u):
        try:
            rr=get(u,timeout=20)
            if len(rr.content)>2_500_000:
                return u,None
            return u,image_feature(rr.content)
        except Exception:
            return u,None
    with ThreadPoolExecutor(max_workers=workers) as ex:
        fut=[ex.submit(one,u) for u in unique]
        for i,f in enumerate(as_completed(fut),1):
            u,feat=f.result()
            if feat: fmap[u]=feat
            if i%100==0:
                print(f"features {i}/{len(unique)} ok={len(fmap)}", flush=True)
    for r in records:
        feat=fmap.get(r["refs"][0],"")
        r["feature"]=feat
        if feat: r["status"]="direct_reference_ready"
    return records

def compact(r):
    meta={"kidsNo":r.get("kidsNo",0),"source":r.get("source",""),"feature":r.get("feature","")}
    return [r["id"],r["dex"],r["pokemon"],r["variant"],r["series"],r["year"],r["visualGroupId"],r["status"],r["refs"],meta]

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("--max-pages",type=int,default=1200)
    ap.add_argument("--max-images",type=int,default=2500)
    ap.add_argument("--no-images",action="store_true")
    args=ap.parse_args()
    existing=load_existing()
    names=load_species_names()
    scraped,pages=crawl(names,args.max_pages)
    dedup={}
    for r in scraped:
        key=(r["source"],r["refs"][0],r["variant"])
        dedup[key]=r
    scraped=list(dedup.values())
    if not args.no_images:
        scraped=attach_features(scraped,args.max_images)
    by_dex={}
    for r in scraped:
        by_dex[r["dex"]]=by_dex.get(r["dex"],0)+1
    stats={
        "existing_records":len(existing),
        "existing_species":len({int(x[1]) for x in existing if len(x)>1 and int(x[1])>0}),
        "pages_attempted":len(pages),
        "pages_with_records":sum(1 for v in pages.values() if v>0),
        "pages_failed":sum(1 for v in pages.values() if v<0),
        "scraped_records":len(scraped),
        "scraped_with_features":sum(1 for r in scraped if r["feature"]),
        "scraped_species":len({r["dex"] for r in scraped if r["dex"]>0}),
        "trainer_or_other":sum(1 for r in scraped if r["dex"]==0),
        "top_dex_counts":sorted(by_dex.items(), key=lambda x:x[1], reverse=True)[:30],
    }
    STATS.write_text(json.dumps(stats,ensure_ascii=False,indent=2),encoding="utf-8")
    OUT.write_text(json.dumps([compact(r) for r in scraped],ensure_ascii=False,separators=(",",":")),encoding="utf-8")
    print(json.dumps(stats,ensure_ascii=False,indent=2))
    if len(scraped)<100:
        print("catalog crawl produced too few records", file=sys.stderr)
        return 2
    return 0

if __name__=="__main__":
    raise SystemExit(main())
