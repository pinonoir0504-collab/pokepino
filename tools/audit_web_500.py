#!/usr/bin/env python3
import argparse, base64, csv, gzip, html, io, json, math, random, re, sys, time, unicodedata
from collections import defaultdict, Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from urllib.parse import quote_plus, urlparse

import numpy as np
import requests
from bs4 import BeautifulSoup
from PIL import Image, ImageOps

ROOT=Path(__file__).resolve().parents[1]
ASSETS=ROOT/"app"/"src"/"main"/"assets"
CAT=ASSETS/"catalog_v2.json"
OUT=ROOT/"web500_audit.json"
CSVOUT=ROOT/"web500_failures.csv"
UA="Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/140 Safari/537.36 PokepinoAudit/1.0"
POKE_SPECIES="https://raw.githubusercontent.com/PokeAPI/pokeapi/master/data/v2/csv/pokemon_species.csv"
POKE_NAMES="https://raw.githubusercontent.com/PokeAPI/pokeapi/master/data/v2/csv/pokemon_species_names.csv"
MODEL_REV="68c055b397ad1a9bc61bbba39be438c4a2ac3f28"
MODEL_BASE=f"https://huggingface.co/BiernyVR/pokemon-classifier-mobilenetv3/resolve/{MODEL_REV}"
MODEL_URL=f"{MODEL_BASE}/pokemon_classifier.onnx?download=true"
LABELS_URL=f"{MODEL_BASE}/pokemon_labels.json?download=true"

SEARCHES=[
    '"Pokemon Kids" "Finger Puppet Figure" BANDAI',
    '"Pokemon Kids" "Finger Puppet" "Vinyl Toy" BANDAI',
    '"Pokemon Kids" "Finger Puppet" Nintendo',
    '"Pokemon Kids" "finger puppet figure" eBay',
    'ポケモンキッズ 指人形 ソフビ 1体',
    'ポケモンキッズ 指人形 バンダイ 中古',
    'ポケモンキッズ 指人形 メルカリ',
    'ポケモンキッズ 指人形 フィギュア',
]
CONTEXT=("pokemon kids","pokémon kids","finger puppet","finger-puppet","ポケモンキッズ","指人形")
MULTI=(" lot "," bundle "," collection "," set of "," figures "," pcs "," pieces ","まとめ","セット","全種","大量","複数")
EXCLUDED_DOMAINS=("yubi-nin.jp",)
session=requests.Session()
session.headers.update({"User-Agent":UA,"Accept-Language":"ja,en-US;q=0.8,en;q=0.7"})

def get(url, timeout=30, max_bytes=None):
    r=session.get(url,timeout=timeout,allow_redirects=True)
    r.raise_for_status()
    if max_bytes is not None and len(r.content)>max_bytes:
        raise ValueError("too large")
    return r

def ascii_norm(s):
    s=unicodedata.normalize("NFKD",s or "")
    s="".join(ch for ch in s if not unicodedata.combining(ch))
    s=s.lower().replace("♀"," female ").replace("♂"," male ")
    return " ".join(re.sub(r"[^a-z0-9]+"," ",s).split())

def jp_norm(s):
    return unicodedata.normalize("NFKC",s or "").lower().replace(" ","").replace("　","")

def load_species():
    species=list(csv.DictReader(io.StringIO(get(POKE_SPECIES).text)))
    names=list(csv.DictReader(io.StringIO(get(POKE_NAMES).text)))
    en={int(r["pokemon_species_id"]):r["name"] for r in names if r["local_language_id"]=="9"}
    ja={int(r["pokemon_species_id"]):r["name"] for r in names if r["local_language_id"]=="1"}
    ident={int(r["id"]):r["identifier"] for r in species}
    aliases=defaultdict(set)
    for dex in set(en)|set(ja)|set(ident):
        if dex in en:
            aliases[dex].add(ascii_norm(en[dex]))
        if dex in ident:
            aliases[dex].add(ascii_norm(ident[dex].replace("-"," ")))
        if dex in ja:
            aliases[dex].add(jp_norm(ja[dex]))
    aliases[29]|={"nidoran female","nidoran f"}
    aliases[32]|={"nidoran male","nidoran m"}
    aliases[83]|={"farfetchd","farfetch d"}
    aliases[122]|={"mr mime","mister mime"}
    aliases[439]|={"mime jr","mime junior"}
    aliases[772]|={"type null"}
    aliases[474]|={"porygon z"}
    aliases[250]|={"ho oh"}
    return en,ja,aliases

def infer_species(text,en,ja,aliases):
    a=ascii_norm(text); j=jp_norm(text)
    hits=set()
    for dex,als in aliases.items():
        for x in als:
            if not x: continue
            if re.search(r"[\u3040-\u30ff\u3400-\u9fff]",x):
                if x in j: hits.add(dex); break
            else:
                if re.search(r"(?<![a-z0-9])"+re.escape(x)+r"(?![a-z0-9])",a):
                    hits.add(dex); break
    # Stronger filtering for substring-related names.
    if 151 in hits and 150 in hits: hits.discard(151) # Mew in Mewtwo noise
    return hits

def ebay_search_results(name):
    q=quote_plus(f'{name} Pokemon Kids Finger Puppet Bandai')
    url=f"https://www.ebay.com/sch/i.html?_nkw={q}&_ipg=120&_sop=10"
    try:
        text=get(url,timeout=25).text
    except Exception as e:
        return []
    soup=BeautifulSoup(text,"html.parser")
    out=[]
    for li in soup.select("li.s-item"):
        a=li.select_one("a.s-item__link")
        title_el=li.select_one(".s-item__title")
        img=li.select_one("img.s-item__image-img, img")
        if not a or not title_el or not img: continue
        href=(a.get("href") or "").split("?")[0]
        src=img.get("data-defer-load") or img.get("data-src") or img.get("src")
        title=title_el.get_text(" ",strip=True)
        if not href.startswith("http") or not src or not src.startswith("http"): continue
        if "shop on ebay" in title.lower(): continue
        out.append({"source_url":href,"image_url":src,"meta":title,"query":name})
    print(f"ebay search {name}: html={len(text)} items={len(out)}",flush=True)
    return out

def bing_web_results(query,pages=1):
    seen=set()
    for p in range(pages):
        first=1+p*10
        url=f"https://www.bing.com/search?q={quote_plus(query)}&first={first}&count=10&setlang=en"
        try:
            text=get(url,timeout=20).text
        except Exception:
            continue
        soup=BeautifulSoup(text,"html.parser")
        found=0
        for li in soup.select("li.b_algo"):
            a=li.select_one("h2 a")
            if not a or not a.get("href"): continue
            u=a.get("href")
            if u in seen: continue
            seen.add(u);found+=1
            title=a.get_text(" ",strip=True)
            snip=li.get_text(" ",strip=True)
            yield {"source_url":u,"meta":title+" "+snip,"query":query}
        if found<3: break
        time.sleep(.10)

def listing_main_image(source_url):
    try:
        raw=get(source_url,timeout=18,max_bytes=3_500_000).text[:1_000_000]
        soup=BeautifulSoup(raw,"html.parser")
        candidates=[]
        for prop in ("og:image","twitter:image","twitter:image:src"):
            x=soup.find("meta",attrs={"property":prop}) or soup.find("meta",attrs={"name":prop})
            if x and x.get("content"): candidates.append(x["content"])
        # eBay occasionally exposes main image through image tags when og:image is generic.
        for img in soup.select('img[id*="icImg"], img[data-zoom-src], img[src*="ebayimg"]'):
            u=img.get("data-zoom-src") or img.get("src")
            if u: candidates.append(u)
        title=(soup.title.get_text(" ",strip=True) if soup.title else "")
        desc=" ".join(
            x.get("content","") for x in soup.find_all(
                "meta",attrs={"property":re.compile("og:title|og:description",re.I)}
            )
        )
        for u in candidates:
            if u and u.startswith("http") and "logo" not in u.lower():
                return u,(title+" "+desc).strip()
    except Exception:
        pass
    return None,""

def collect_species_list(en,ja):
    runtime=load_runtime_catalog()
    dexes=sorted({x["dex"] for x in runtime if x["dex"]>0 and x["dex"] in en})
    rng=random.Random(20261005)
    rng.shuffle(dexes)
    return dexes

def bing_results(query,pages=12):
    seen=set()
    for p in range(pages):
        first=1+p*35
        url=f"https://www.bing.com/images/search?q={quote_plus(query)}&first={first}&count=35&form=HDRSC3"
        try:
            text=get(url,timeout=25).text
        except Exception:
            time.sleep(1.0); continue
        soup=BeautifulSoup(text,"html.parser")
        found=0
        for a in soup.select("a.iusc, a[m]"):
            raw=a.get("m")
            if not raw: continue
            try: m=json.loads(html.unescape(raw))
            except Exception: continue
            murl=m.get("murl") or m.get("imgurl")
            purl=m.get("purl") or m.get("surl")
            if not murl or not purl: continue
            key=(murl,purl)
            if key in seen: continue
            seen.add(key);found+=1
            title=" ".join(str(m.get(k) or "") for k in ("t","desc","md5"))
            img=a.find("img")
            if img:
                title+=" "+(img.get("alt") or "")
            yield {"image_url":murl,"source_url":purl,"meta":title.strip(),"query":query}
        if found<4: break
        time.sleep(.15)

def source_context(candidate):
    source=candidate["source_url"]
    dom=urlparse(source).netloc.lower()
    if any(x in dom for x in EXCLUDED_DOMAINS):
        return None
    text=(candidate.get("meta","")+" "+source).strip()
    low=text.lower()
    if not any(x in low for x in CONTEXT):
        # Fetch a small slice of the source page and use title/meta description.
        try:
            raw=get(source,timeout=15,max_bytes=2_000_000).text[:500_000]
            s=BeautifulSoup(raw,"html.parser")
            page=" ".join([
                s.title.get_text(" ",strip=True) if s.title else "",
                *(x.get("content","") for x in s.find_all("meta",attrs={"name":re.compile("description",re.I)})),
                *(x.get("content","") for x in s.find_all("meta",attrs={"property":re.compile("og:title|og:description",re.I)})),
            ])
            text+=" "+page
            low=text.lower()
        except Exception:
            pass
    if not any(x in low for x in CONTEXT):
        return None
    return text

def decode_image(url):
    r=get(url,timeout=25,max_bytes=7_500_000)
    ctype=r.headers.get("content-type","").lower()
    if "image" not in ctype and not re.search(r"\.(jpg|jpeg|png|webp)(?:\?|$)",url,re.I):
        raise ValueError("not image")
    im=Image.open(io.BytesIO(r.content)).convert("RGB")
    if min(im.size)<160 or max(im.size)>5000:
        raise ValueError("bad dimensions")
    ratio=max(im.size)/max(1,min(im.size))
    if ratio>2.5: raise ValueError("extreme aspect")
    return im

def phashish(im):
    a=np.asarray(ImageOps.fit(im.convert("L"),(16,16),method=Image.Resampling.BILINEAR),dtype=np.float32)
    return (a>a.mean()).astype(np.uint8).tobytes()

def parse_kids_no(text):
    pats=[
        r"(?:pokemon\s*kids|ポケモンキッズ)[^\n\r]{0,60}?(?:no\.?|#)\s*0*(\d{1,4})",
        r"(?:kids\s*no\.?|キッズ\s*no\.?)\s*0*(\d{1,4})",
    ]
    for p in pats:
        m=re.search(p,text,re.I)
        if m:
            v=int(m.group(1))
            if 1<=v<=2000:return v
    return 0

def collect(target,en,ja,aliases,max_pages):
    seen_urls=set();seen_sources=set();seen_hashes=set();stats=Counter()
    per_species=Counter();per_domain=Counter();out=[]

    dexes=collect_species_list(en,ja)[:320]
    # Primary source: eBay result cards provide title + listing URL + image URL as
    # one bound record, which is ideal for a labeled image audit.
    def search_one(dex):
        name=en[dex]
        direct=ebay_search_results(name)
        if direct:
            return [(dex,x) for x in direct]
        # Fallback only if eBay search is unavailable for this query.
        q=f'site:ebay.com/itm "{name}" "Pokemon Kids" "Finger Puppet" Bandai'
        try:return [(dex,x) for x in bing_web_results(q,pages=1)]
        except Exception:return []

    candidates=[]
    with ThreadPoolExecutor(max_workers=8) as ex:
        fut={ex.submit(search_one,d):d for d in dexes}
        for n,f in enumerate(as_completed(fut),1):
            try:candidates.extend(f.result())
            except Exception:stats["search_fail"]+=1
            if n%100==0:print(f"searches {n}/{len(dexes)} candidates={len(candidates)}",flush=True)
    random.Random(20261005).shuffle(candidates)

    # Cheap metadata filtering before touching listing/image URLs.
    filtered=[]
    for expected_dex,cand in candidates:
        source=cand["source_url"]
        if source in seen_sources:continue
        dom=urlparse(source).netloc.lower().replace("www.","")
        if any(x in dom for x in EXCLUDED_DOMAINS):continue
        text=cand.get("meta","")
        low=text.lower()
        if not any(x in low for x in CONTEXT):
            stats["no_context_search"]+=1;continue
        hits=infer_species(text,en,ja,aliases)
        if hits!={expected_dex}:
            stats["label_ambiguous_search"]+=1;continue
        alow=" "+ascii_norm(text)+" "
        if any(x in alow for x in MULTI):
            stats["multi_title"]+=1;continue
        seen_sources.add(source)
        filtered.append((expected_dex,cand))
    print(f"metadata-qualified candidates={len(filtered)}",flush=True)

    def hydrate(pair):
        expected_dex,cand=pair
        source=cand["source_url"]
        image_url=cand.get("image_url")
        page_meta=""
        if not image_url:
            image_url,page_meta=listing_main_image(source)
        if not image_url:return None,"no_main_image"
        text=(cand.get("meta","")+" "+page_meta).strip()
        hits=infer_species(text,en,ja,aliases)
        if hits!={expected_dex}:return None,"label_ambiguous_page"
        try:im=decode_image(image_url)
        except Exception:return None,"image_fail"
        return (expected_dex,cand,image_url,text,im),None

    # Fetch many listing pages/images concurrently. We oversample because dead listings are common.
    hydrated=[]
    max_hydrate=min(len(filtered),max(target*5,1800))
    with ThreadPoolExecutor(max_workers=20) as ex:
        fut=[ex.submit(hydrate,p) for p in filtered[:max_hydrate]]
        for n,f in enumerate(as_completed(fut),1):
            try:item,err=f.result()
            except Exception:
                item,err=None,"hydrate_exception"
            if err:stats[err]+=1
            elif item:hydrated.append(item)
            if n%200==0:print(f"hydrated {n}/{max_hydrate} usable={len(hydrated)}",flush=True)

    random.Random(20261006).shuffle(hydrated)
    for expected_dex,cand,image_url,text,im in hydrated:
        if len(out)>=target:break
        dex=expected_dex
        dom=urlparse(cand["source_url"]).netloc.lower().replace("www.","")
        if per_species[dex]>=4:stats["species_cap"]+=1;continue
        if per_domain[dom]>=220:stats["domain_cap"]+=1;continue
        if image_url in seen_urls:continue
        h=phashish(im)
        if h in seen_hashes:stats["duplicate"]+=1;continue
        seen_hashes.add(h);seen_urls.add(image_url)
        per_species[dex]+=1;per_domain[dom]+=1
        out.append({
            "target_dex":dex,
            "target_name_ja":ja.get(dex,""),
            "target_name_en":en.get(dex,""),
            "image_url":image_url,
            "source_url":cand["source_url"],
            "source_domain":dom,
            "kids_no":parse_kids_no(text),
            "year":(re.search(r"\b(199[6-9]|20[0-2]\d)\b",text) or [None,""])[1],
            "is_clear":bool(re.search(r"\bclear\b|クリア",text,re.I)),
            "label_text":re.sub(r"\s+"," ",text)[:500],
        })
        if len(out)%50==0:print(f"accepted {len(out)}/{target} species={len(per_species)} domains={len(per_domain)}",flush=True)

    # Fallback generic image search only if the species-specific listings weren't enough.
    if len(out)<target:
        generic=[]
        for q in SEARCHES:
            generic.extend((None,x) for x in bing_results(q,pages=max_pages))
        for _,cand in generic:
            if len(out)>=target:break
            source=cand["source_url"]
            dom=urlparse(source).netloc.lower().replace("www.","")
            if any(x in dom for x in EXCLUDED_DOMAINS):continue
            text=source_context(cand)
            if not text:continue
            hits=infer_species(text,en,ja,aliases)
            if len(hits)!=1:continue
            dex=next(iter(hits))
            if per_species[dex]>=4:continue
            try:im=decode_image(cand["image_url"])
            except Exception:continue
            h=phashish(im)
            if h in seen_hashes:continue
            seen_hashes.add(h)
            out.append({
                "target_dex":dex,"target_name_ja":ja.get(dex,""),"target_name_en":en.get(dex,""),
                "image_url":cand["image_url"],"source_url":source,"source_domain":dom,
                "kids_no":parse_kids_no(text),
                "year":(re.search(r"\b(199[6-9]|20[0-2]\d)\b",text) or [None,""])[1],
                "is_clear":bool(re.search(r"\bclear\b|クリア",text,re.I)),
                "label_text":re.sub(r"\s+"," ",text)[:500],
            })
            per_species[dex]+=1

    print("collection stats",dict(stats),flush=True)
    return out

def decode_sig(encoded):
    raw=base64.b64decode(encoded)
    blocks=np.frombuffer(raw[:192],dtype=np.uint8).astype(np.float32)/255.0
    hist=np.frombuffer(raw[192:232],dtype=np.uint8).astype(np.float32)/255.0
    edge=np.frombuffer(raw[232:264],dtype=np.uint8)
    return blocks,hist,edge

def feature(im):
    im=im.convert("RGB")
    arr=np.asarray(im,dtype=np.float32)
    h,w,_=arr.shape
    border=np.concatenate([arr[0,:,:],arr[-1,:,:],arr[:,0,:],arr[:,-1,:]],axis=0)
    bg=np.median(border,axis=0)
    dist=np.linalg.norm(arr-bg,axis=2)
    mask=dist>max(28.0,float(np.percentile(dist,58)))
    ys,xs=np.where(mask)
    bbox_ratio=1.0
    if len(xs)>max(20,h*w*.015):
        x0,x1=max(0,int(xs.min())-2),min(w,int(xs.max())+3)
        y0,y1=max(0,int(ys.min())-2),min(h,int(ys.max())+3)
        bbox_ratio=((x1-x0)*(y1-y0))/(w*h)
        im=im.crop((x0,y0,x1,y1))
    im=im.resize((32,32),Image.Resampling.LANCZOS)
    a=np.asarray(im,dtype=np.float32)
    blocks=a.reshape(8,4,8,4,3).mean(axis=(1,3)).clip(0,255).astype(np.uint8).reshape(-1).astype(np.float32)/255.0
    hsv=np.asarray(im.convert("HSV"),dtype=np.uint8)
    hist=[]
    for channel,bins in [(0,24),(1,8),(2,8)]:
        x,_=np.histogram(hsv[:,:,channel],bins=bins,range=(0,256))
        x=x.astype(np.float32)/max(1,x.max())
        hist.extend(x.tolist())
    gray=np.asarray(im.resize((16,16),Image.Resampling.BILINEAR).convert("L"),dtype=np.int16)
    gx=np.abs(np.diff(gray,axis=1,prepend=gray[:,:1]))
    gy=np.abs(np.diff(gray,axis=0,prepend=gray[:1,:]))
    edge=np.packbits(((gx+gy)>38).astype(np.uint8).reshape(-1))
    return (blocks,np.asarray(hist,dtype=np.float32),edge),bbox_ratio,float(np.std(border))

def cos(a,b):
    den=float(np.linalg.norm(a)*np.linalg.norm(b))
    return float(np.dot(a,b)/den) if den else 0.0

def components(a,b):
    ab,ah,ae=a;bb,bh,be=b
    color=cos(ab,bb);hist=cos(ah,bh)
    diff=np.bitwise_xor(ae,be)
    edge=(256-sum(int(x).bit_count() for x in diff.tolist()))/256.0
    return color,hist,edge

def load_runtime_catalog():
    parts=["dbgz_00.txt","dbgz_01a.txt","dbgz_01b.txt","dbgz_02a.txt","dbgz_02b.txt","dbgz_03a.txt","dbgz_03b.txt"]
    raw="".join((ASSETS/p).read_text(encoding="utf-8") for p in parts)
    base=json.loads(gzip.decompress(base64.b64decode(raw)).decode("utf-8"))
    gen=json.loads(CAT.read_text(encoding="utf-8"))
    def row_to_dict(r):
        meta=r[9] if len(r)>9 and isinstance(r[9],dict) else {}
        return {
            "id":str(r[0]),"dex":int(r[1]),"pokemon":str(r[2]),"variant":str(r[3]),
            "series":str(r[4]),"year":str(r[5]),"group":str(r[6]),"status":str(r[7]),
            "refs":list(r[8] or []),"kids_no":int(meta.get("kidsNo",0) or 0),
            "feature":str(meta.get("feature","") or ""),"source":str(meta.get("source","") or ""),
        }
    b=[row_to_dict(r) for r in base];g=[row_to_dict(r) for r in gen]
    g_by_ref={}
    for x in g:
        for u in x["refs"]:g_by_ref.setdefault(u,x)
    consumed=set()
    for x in b:
        match=next((g_by_ref[u] for u in x["refs"] if u in g_by_ref),None)
        if match:
            consumed.add(match["id"])
            x.update({
                "group":match["group"],"status":"direct_reference_ready" if match["feature"] else x["status"],
                "kids_no":match["kids_no"] or x["kids_no"],"feature":match["feature"] or x["feature"],
                "source":match["source"] or x["source"],
            })
    concrete={x["group"] for x in g if x["series"]!="ポケモンキッズ一覧"}
    extra=[x for x in g if x["id"] not in consumed and not (x["series"]=="ポケモンキッズ一覧" and x["group"] in concrete)]
    seen=set();out=[]
    for x in b+extra:
        if x["id"] and x["id"] not in seen:
            seen.add(x["id"]);out.append(x)
    return out

def prepare_matcher(runtime):
    refs=[x for x in runtime if x["dex"]>0 and x["feature"]]
    sigs=[decode_sig(x["feature"]) for x in refs]
    by_group=defaultdict(list)
    for i,x in enumerate(refs):by_group[x["group"]].append(i)
    return refs,sigs,by_group

def visual_rank(q,refs,sigs,by_group,weights=(.55,.25,.20),limit=7,allowed_dex=None):
    scores=np.empty(len(sigs),dtype=np.float32)
    for i,s in enumerate(sigs):
        if allowed_dex is not None and refs[i]["dex"]!=allowed_dex:
            scores[i]=-1
        else:
            co,hi,ed=components(q,s);scores[i]=weights[0]*co+weights[1]*hi+weights[2]*ed
    groups=[]
    for gid,idxs in by_group.items():
        valid=[i for i in idxs if scores[i]>=0]
        if not valid:continue
        best=max(valid,key=lambda i:float(scores[i]))
        groups.append((gid,float(scores[best]),[refs[i] for i in valid]))
    groups.sort(key=lambda x:x[1],reverse=True)
    return groups[:limit]

def load_ai():
    import tempfile, onnxruntime as ort
    td=Path(tempfile.mkdtemp(prefix="pokepino_audit_"))
    model=td/"model.onnx";labels=td/"labels.json"
    model.write_bytes(get(MODEL_URL,timeout=180,max_bytes=40_000_000).content)
    labels.write_bytes(get(LABELS_URL,timeout=60,max_bytes=2_000_000).content)
    lab=json.loads(labels.read_text())
    ids=[int(x.get("id",i+1)) for i,x in enumerate(lab)]
    sess=ort.InferenceSession(str(model),providers=["CPUExecutionProvider"])
    return sess,ids

def ai_predict(sess,labels,im):
    x=np.asarray(im.convert("RGB").resize((224,224),Image.Resampling.BILINEAR),dtype=np.float32)/255.
    x=(x-np.array([.485,.456,.406],dtype=np.float32))/np.array([.229,.224,.225],dtype=np.float32)
    x=np.transpose(x,(2,0,1))[None].astype(np.float32)
    logits=np.asarray(sess.run(None,{sess.get_inputs()[0].name:x})[0]).reshape(-1)
    z=logits-logits.max();p=np.exp(z);p/=p.sum()
    order=np.argsort(p)[::-1][:5]
    cs=[(labels[int(i)] if int(i)<len(labels) else int(i)+1,float(p[int(i)])) for i in order]
    margin=cs[0][1]-(cs[1][1] if len(cs)>1 else 0)
    return cs,cs[0][1]>=.35 and margin>=.10

def evaluate(samples,runtime,weights=(.55,.25,.20),with_ai=True):
    refs,sigs,by_group=prepare_matcher(runtime)
    species_counts=Counter(x["dex"] for x in runtime if x["dex"]>0)
    sess=labels=None
    if with_ai:
        try:sess,labels=load_ai()
        except Exception as e:
            print("AI unavailable",repr(e),flush=True)
    results=[]
    stats=Counter()
    for n,s in enumerate(samples,1):
        try: im=decode_image(s["image_url"])
        except Exception as e:
            s["eval_error"]=f"image:{e}";results.append(s);continue
        q,bbox_ratio,border_std=feature(im)
        ranked=visual_rank(q,refs,sigs,by_group,weights)
        top=ranked[0] if ranked else None;second=ranked[1] if len(ranked)>1 else None
        top_score=top[1] if top else 0;margin=top_score-(second[1] if second else 0)
        accepted=bool(top and top_score>=.90 and margin>=.03)
        pred_dex=top[2][0]["dex"] if top else 0
        top5_dex=[]
        for g in ranked:
            d=g[2][0]["dex"]
            if d not in top5_dex:top5_dex.append(d)
        stats["visual_total"]+=1
        stats["visual_top1"]+=pred_dex==s["target_dex"]
        stats["visual_top5"]+=s["target_dex"] in top5_dex[:5]
        if accepted:
            stats["visual_accepted"]+=1
            stats["visual_accepted_correct"]+=pred_dex==s["target_dex"]

        final_auto=False;final_dex=0;final_mode="none";ai_info=[]
        if accepted and top:
            if len(top[2])==1:
                final_auto=True;final_dex=pred_dex;final_mode="visual"
        elif sess is not None:
            ai_info,ai_ok=ai_predict(sess,labels,im)
            if ai_ok:
                ai_dex=ai_info[0][0]
                if species_counts[ai_dex]==1:
                    final_auto=True;final_dex=ai_dex;final_mode="ai_single"
                else:
                    local=visual_rank(q,refs,sigs,by_group,weights,allowed_dex=ai_dex)
                    la=local[0] if local else None;lb=local[1] if len(local)>1 else None
                    lm=(la[1]-(lb[1] if lb else 0)) if la else 0
                    if la and la[1]>=.90 and lm>=.03 and len(la[2])==1:
                        final_auto=True;final_dex=ai_dex;final_mode="ai_visual"
        if final_auto:
            stats["auto_total"]+=1
            stats["auto_correct"]+=final_dex==s["target_dex"]
        if pred_dex!=s["target_dex"]:stats["visual_miss"]+=1
        exact_known=False;exact_correct=False
        matches=[]
        if s.get("kids_no"):
            matches=[x for x in runtime if x["dex"]==s["target_dex"] and x["kids_no"]==s["kids_no"]]
        elif s.get("year"):
            y=str(s["year"])
            matches=[x for x in runtime if x["dex"]==s["target_dex"] and x["year"]==y]
            if s.get("is_clear"):
                clear=[x for x in matches if "クリア" in (x["variant"]+x["series"])]
                if clear:matches=clear
        target_groups={x["group"] for x in matches}
        if len(target_groups)==1:
            exact_known=True
            exact_correct=bool(top and top[0] in target_groups)
            stats["exact_known"]+=1;stats["exact_correct"]+=exact_correct
        r=dict(s)
        r.update({
            "visual_pred_dex":pred_dex,"visual_pred_name":top[2][0]["pokemon"] if top else "",
            "visual_score":round(top_score,5),"visual_margin":round(margin,5),"visual_accepted":accepted,
            "visual_top5_dex":top5_dex[:5],"auto_registered":final_auto,"auto_dex":final_dex,
            "auto_mode":final_mode,"auto_correct_species":(final_auto and final_dex==s["target_dex"]),
            "ai_top5":ai_info,"bbox_ratio":round(bbox_ratio,4),"border_std":round(border_std,2),
            "exact_known":exact_known,"exact_correct":exact_correct,
        })
        results.append(r)
        if n%50==0:print(f"evaluated {n}/{len(samples)}",flush=True)
    metrics={
        "samples":stats["visual_total"],
        "visual_species_top1":stats["visual_top1"]/max(1,stats["visual_total"]),
        "visual_species_top5":stats["visual_top5"]/max(1,stats["visual_total"]),
        "visual_accept_rate":stats["visual_accepted"]/max(1,stats["visual_total"]),
        "visual_precision_when_accepted":stats["visual_accepted_correct"]/max(1,stats["visual_accepted"]),
        "full_app_auto_rate":stats["auto_total"]/max(1,stats["visual_total"]),
        "full_app_auto_precision_species":stats["auto_correct"]/max(1,stats["auto_total"]),
        "exact_labeled_subset":stats["exact_known"],
        "exact_top1_accuracy":stats["exact_correct"]/max(1,stats["exact_known"]),
        "visual_misses":stats["visual_miss"],
    }
    return metrics,results

def diagnose(results):
    misses=[r for r in results if r.get("visual_pred_dex")!=r.get("target_dex") and "visual_pred_dex" in r]
    c=Counter()
    for r in misses:
        if r.get("bbox_ratio",1)<.25:c["small_subject_or_bad_crop"]+=1
        if r.get("border_std",0)>55:c["complex_background"]+=1
        if r.get("visual_score",0)>.92:c["confident_wrong_neighbor"]+=1
        if r.get("target_dex") in r.get("visual_top5_dex",[]):c["right_species_in_top5"]+=1
        if r.get("auto_registered") and not r.get("auto_correct_species"):c["dangerous_wrong_auto"]+=1
    conf=Counter((r.get("target_dex"),r.get("visual_pred_dex")) for r in misses)
    return {"failure_buckets":dict(c),"top_confusions":[{"target":a,"pred":b,"count":n} for (a,b),n in conf.most_common(20)]}

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("--target",type=int,default=500)
    ap.add_argument("--pages",type=int,default=15)
    ap.add_argument("--no-ai",action="store_true")
    args=ap.parse_args()
    en,ja,aliases=load_species()
    samples=collect(args.target,en,ja,aliases,args.pages)
    if len(samples)<args.target:
        print(f"Only collected {len(samples)} external images",file=sys.stderr)
    if len(samples)<350:
        raise SystemExit("Not enough independently sourced images for a meaningful 500-image audit")
    runtime=load_runtime_catalog()
    metrics,results=evaluate(samples,runtime,with_ai=not args.no_ai)
    payload={
        "method":"Bing image discovery; yubi-nin excluded; one unambiguous species label from listing/search metadata; no external image bytes retained",
        "requested":args.target,"collected":len(samples),
        "unique_species":len({x["target_dex"] for x in samples}),
        "unique_domains":len({x["source_domain"] for x in samples}),
        "metrics":metrics,
        "diagnosis":diagnose(results),
        "results":results,
    }
    OUT.write_text(json.dumps(payload,ensure_ascii=False,indent=2),encoding="utf-8")
    misses=[r for r in results if r.get("visual_pred_dex")!=r.get("target_dex") or (r.get("auto_registered") and not r.get("auto_correct_species"))]
    with CSVOUT.open("w",newline="",encoding="utf-8-sig") as f:
        cols=["target_dex","target_name_ja","source_domain","source_url","image_url","visual_pred_dex","visual_pred_name","visual_score","visual_margin","visual_top5_dex","auto_registered","auto_dex","auto_mode","auto_correct_species","bbox_ratio","border_std","kids_no","exact_known","exact_correct"]
        w=csv.DictWriter(f,fieldnames=cols,extrasaction="ignore");w.writeheader()
        for r in misses:
            rr=dict(r);rr["visual_top5_dex"]=json.dumps(rr.get("visual_top5_dex",[]));w.writerow(rr)
    print(json.dumps({k:v for k,v in payload.items() if k!="results"},ensure_ascii=False,indent=2))
    return 0

if __name__=="__main__":
    raise SystemExit(main())
