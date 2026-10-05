#!/usr/bin/env python3
import base64,csv,gzip,io,json,re,sys,unicodedata,hashlib,math,time
from collections import Counter,defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
import numpy as np
import requests
from PIL import Image,ImageOps

ROOT=Path(__file__).resolve().parents[1]
ASSETS=ROOT/"app"/"src"/"main"/"assets"
CAND=ROOT/"audit"/"marketplace_500_candidates.json"
OUT=ROOT/"marketplace500_audit.json"
FAIL=ROOT/"marketplace500_failures.csv"
POKE_NAMES="https://raw.githubusercontent.com/PokeAPI/pokeapi/master/data/v2/csv/pokemon_species_names.csv"
MODEL_REV="68c055b397ad1a9bc61bbba39be438c4a2ac3f28"
BASE=f"https://huggingface.co/BiernyVR/pokemon-classifier-mobilenetv3/resolve/{MODEL_REV}"
MODEL_URL=f"{BASE}/pokemon_classifier.onnx?download=true"
LABELS_URL=f"{BASE}/pokemon_labels.json?download=true"
UA="Mozilla/5.0 PokepinoMarketplaceAudit/1.0"
s=requests.Session();s.headers.update({"User-Agent":UA,"Accept-Language":"ja,en;q=0.7"})
_IMG_CACHE={}

def get(url,timeout=30,max_bytes=None):
    r=s.get(url,timeout=timeout);r.raise_for_status()
    if max_bytes is not None and len(r.content)>max_bytes:raise ValueError("too large")
    return r

def norm(s):
    return unicodedata.normalize("NFKC",s or "").replace(" ","").replace("　","").lower()

def species_names():
    text=get(POKE_NAMES,30,5_000_000).text
    names={}
    for r in csv.DictReader(io.StringIO(text)):
        if r["local_language_id"]=="1":
            names[int(r["pokemon_species_id"])]=r["name"]
    # longest first avoids ピチュー/ピカチュウ-style partial surprises
    return names,sorted(names.items(),key=lambda x:len(x[1]),reverse=True)

def infer_one(text,ordered):
    t=norm(text)
    hits=[]
    for dex,name in ordered:
        n=norm(name)
        if n and n in t:hits.append((dex,name))
    # remove names fully contained in a longer hit occurring in the same token
    uniq=[]
    for dex,name in hits:
        if not any(name!=n2 and norm(name) in norm(n2) for _,n2 in hits):
            uniq.append((dex,name))
    return uniq

BAD=re.compile(r"カード|シール|ステッカー|図鑑|本\\b|パンフ|ポスター|ケース|空箱|箱のみ|パッケージのみ|未開封|箱入|箱入り|袋未開封",re.I)
MULTI=re.compile(r"まとめ|セット|大量|詰め合わせ|コンプリート|全種|\d+\s*体|\d+\s*個|＆|&|\+|、|・.+・",re.I)
FIG=re.compile(r"ポケモン\s*キッズ|指人形|ソフビ",re.I)

def download_image(url):
    cached=_IMG_CACHE.get(url)
    if cached is not None:return cached.copy()
    r=get(url,20,8_000_000)
    im=Image.open(io.BytesIO(r.content)).convert("RGB")
    if min(im.size)<120:raise ValueError("small")
    _IMG_CACHE[url]=im.copy()
    return im

def ahash(im):
    a=np.asarray(ImageOps.fit(im.convert("L"),(16,16),method=Image.Resampling.BILINEAR),dtype=np.float32)
    bits=(a>a.mean()).astype(np.uint8)
    return hashlib.sha1(np.packbits(bits.reshape(-1)).tobytes()).hexdigest()

def decode_sig(encoded):
    raw=base64.b64decode(encoded)
    return (np.frombuffer(raw[:192],dtype=np.uint8).astype(np.float32)/255.,
            np.frombuffer(raw[192:232],dtype=np.uint8).astype(np.float32)/255.,
            np.frombuffer(raw[232:264],dtype=np.uint8))

def feature(im):
    im=im.convert("RGB")
    arr=np.asarray(im,dtype=np.float32);h,w,_=arr.shape
    border=np.concatenate([arr[0],arr[-1],arr[:,0],arr[:,-1]],axis=0)
    bg=np.median(border,axis=0);dist=np.linalg.norm(arr-bg,axis=2)
    mask=dist>max(28.,float(np.percentile(dist,58)))
    ys,xs=np.where(mask);bbox_ratio=1.
    if len(xs)>max(20,h*w*.015):
        x0,x1=max(0,int(xs.min())-2),min(w,int(xs.max())+3)
        y0,y1=max(0,int(ys.min())-2),min(h,int(ys.max())+3)
        bbox_ratio=((x1-x0)*(y1-y0))/(w*h);im=im.crop((x0,y0,x1,y1))
    im=im.resize((32,32),Image.Resampling.LANCZOS)
    a=np.asarray(im,dtype=np.float32)
    blocks=a.reshape(8,4,8,4,3).mean(axis=(1,3)).clip(0,255).astype(np.uint8).reshape(-1).astype(np.float32)/255.
    hsv=np.asarray(im.convert("HSV"),dtype=np.uint8);hist=[]
    for ch,bins in [(0,24),(1,8),(2,8)]:
        x,_=np.histogram(hsv[:,:,ch],bins=bins,range=(0,256));x=x.astype(np.float32)/max(1,x.max());hist.extend(x.tolist())
    g=np.asarray(im.resize((16,16),Image.Resampling.BILINEAR).convert("L"),dtype=np.int16)
    gx=np.abs(np.diff(g,axis=1,prepend=g[:,:1]));gy=np.abs(np.diff(g,axis=0,prepend=g[:1,:]))
    edge=np.packbits(((gx+gy)>38).astype(np.uint8).reshape(-1))
    return (blocks,np.asarray(hist,dtype=np.float32),edge),bbox_ratio,float(np.std(border))

def cos(a,b):
    d=float(np.linalg.norm(a)*np.linalg.norm(b))
    return float(np.dot(a,b)/d) if d else 0.

def comp(a,b):
    ab,ah,ae=a;bb,bh,be=b
    color=cos(ab,bb);hist=cos(ah,bh)
    equal=256-sum(int(x).bit_count() for x in np.bitwise_xor(ae,be).tolist())
    return color,hist,equal/256.

def runtime_catalog():
    parts=["dbgz_00.txt","dbgz_01a.txt","dbgz_01b.txt","dbgz_02a.txt","dbgz_02b.txt","dbgz_03a.txt","dbgz_03b.txt"]
    raw="".join((ASSETS/p).read_text() for p in parts)
    base=json.loads(gzip.decompress(base64.b64decode(raw)).decode())
    gen=json.loads((ASSETS/"catalog_v2.json").read_text())
    def row(r):
        m=r[9] if len(r)>9 and isinstance(r[9],dict) else {}
        return {"id":str(r[0]),"dex":int(r[1]),"pokemon":str(r[2]),"variant":str(r[3]),"series":str(r[4]),"year":str(r[5]),
                "group":str(r[6]),"status":str(r[7]),"refs":list(r[8] or []),"kids_no":int(m.get("kidsNo",0) or 0),
                "feature":str(m.get("feature","") or ""),"source":str(m.get("source","") or "")}
    b=[row(r) for r in base];g=[row(r) for r in gen]
    byref={}
    for x in g:
        for u in x["refs"]:byref.setdefault(u,x)
    consumed=set()
    for x in b:
        m=next((byref[u] for u in x["refs"] if u in byref),None)
        if m:
            consumed.add(m["id"]);x["group"]=m["group"];x["feature"]=m["feature"] or x["feature"];x["kids_no"]=m["kids_no"] or x["kids_no"]
    concrete={x["group"] for x in g if x["series"]!="ポケモンキッズ一覧"}
    extra=[x for x in g if x["id"] not in consumed and not(x["series"]=="ポケモンキッズ一覧" and x["group"] in concrete)]
    seen=set();out=[]
    for x in b+extra:
        if x["id"] and x["id"] not in seen:seen.add(x["id"]);out.append(x)
    return out

def matcher(runtime):
    refs=[x for x in runtime if x["dex"]>0 and x["feature"]]
    sigs=[decode_sig(x["feature"]) for x in refs]
    groups=defaultdict(list)
    for i,x in enumerate(refs):groups[x["group"]].append(i)
    return refs,sigs,groups

def rank(q,refs,sigs,groups,weights=(.55,.25,.20),allowed=None):
    vals=np.empty(len(sigs),dtype=np.float32)
    for i,sg in enumerate(sigs):
        if allowed is not None and refs[i]["dex"]!=allowed:vals[i]=-1;continue
        a,b,c=comp(q,sg);vals[i]=weights[0]*a+weights[1]*b+weights[2]*c
    out=[]
    for gid,idxs in groups.items():
        valid=[i for i in idxs if vals[i]>=0]
        if not valid:continue
        best=max(valid,key=lambda i:float(vals[i]))
        out.append((gid,float(vals[best]),[refs[i] for i in valid]))
    return sorted(out,key=lambda x:x[1],reverse=True)[:7]

def load_ai():
    import tempfile,onnxruntime as ort
    d=Path(tempfile.mkdtemp());mp=d/"m.onnx";lp=d/"l.json"
    mp.write_bytes(get(MODEL_URL,180,40_000_000).content);lp.write_bytes(get(LABELS_URL,60,2_000_000).content)
    lab=json.loads(lp.read_text());ids=[int(x.get("id",i+1)) for i,x in enumerate(lab)]
    return ort.InferenceSession(str(mp),providers=["CPUExecutionProvider"]),ids

def ai(sess,labels,im):
    x=np.asarray(im.resize((224,224),Image.Resampling.BILINEAR),dtype=np.float32)/255.
    x=(x-np.array([.485,.456,.406],dtype=np.float32))/np.array([.229,.224,.225],dtype=np.float32)
    x=np.transpose(x,(2,0,1))[None].astype(np.float32)
    z=np.asarray(sess.run(None,{sess.get_inputs()[0].name:x})[0]).reshape(-1);z=z-z.max();p=np.exp(z);p/=p.sum()
    ix=np.argsort(p)[::-1][:5];cs=[(labels[int(i)],float(p[int(i)])) for i in ix]
    margin=cs[0][1]-cs[1][1]
    return cs,cs[0][1]>=.35 and margin>=.10

def exact_label(text,dex,runtime):
    # Only claim exact figure identity when listing provides a Kids number or enough
    # unique release descriptors to map to one visual group.
    m=re.search(r"(?:No\.?|№|ナンバー)\s*[-:]?\s*0*(\d{1,4})",text,re.I)
    cand=[x for x in runtime if x["dex"]==dex]
    if m:
        no=int(m.group(1));cand=[x for x in cand if x["kids_no"]==no]
    else:
        ym=re.search(r"\b(199[6-9]|20[0-2]\d)\b",text)
        if ym:cand=[x for x in cand if x["year"]==ym.group(1)]
        if "クリア" in text:cand=[x for x in cand if "クリア" in x["variant"]+x["series"]]
        if "キメわざ" in text:cand=[x for x in cand if "キメわざ" in x["variant"]+x["series"]]
    gs={x["group"] for x in cand}
    return next(iter(gs)) if len(gs)==1 else ""

def main():
    names,ordered=species_names();raw=json.loads(CAND.read_text())
    runtime=runtime_catalog();refs,sigs,groups=matcher(runtime)
    kept=[];seen_hash=set();reject=Counter()
    pre=[]
    for item in sorted(raw,key=lambda x:x["url"]):
        text=(item.get("title","")+" "+item.get("snippet","")).strip()
        if not FIG.search(text):reject["not_figure"]+=1;continue
        if BAD.search(text):reject["non_figure_product"]+=1;continue
        hits=infer_one(text,ordered)
        if len(hits)!=1:reject["species_not_unique"]+=1;continue
        if MULTI.search(item.get("title","")):reject["multi_listing"]+=1;continue
        pre.append((item,text,hits[0]))

    def fetch_one(row):
        item,text,hit=row
        try:
            im=download_image(item["thumbnail_url"])
            return row,ahash(im),None
        except Exception as e:
            return row,None,str(e)

    fetched={}
    with ThreadPoolExecutor(max_workers=20) as ex:
        fut=[ex.submit(fetch_one,row) for row in pre]
        for i,x in enumerate(as_completed(fut),1):
            row,h,err=x.result()
            fetched[row[0]["url"]]=(h,err)
            if i%100==0:print(f"selection images {i}/{len(pre)}",flush=True)

    for item,text,(dex,name) in pre:
        h,err=fetched.get(item["url"],(None,"missing"))
        if err or not h:reject["image_download"]+=1;continue
        if h in seen_hash:reject["duplicate_image"]+=1;continue
        seen_hash.add(h)
        kept.append({**item,"target_dex":dex,"target_name":name,"exact_group":exact_label(text,dex,runtime),"image_hash":h})
        if len(kept)>=500:break
    print("selection",json.dumps({"raw":len(raw),"kept":len(kept),"reject":dict(reject)},ensure_ascii=False),flush=True)
    if len(kept)<500:
        (ROOT/"marketplace500_selection.json").write_text(json.dumps(kept,ensure_ascii=False,indent=2))
        raise SystemExit(f"Need 500 valid single-figure listings, only {len(kept)} available")

    try:sess,labels=load_ai()
    except Exception as e:sess=labels=None;print("AI unavailable",e,flush=True)
    species_counts=Counter(x["dex"] for x in runtime if x["dex"]>0)
    metrics=Counter();rows=[]
    for i,item in enumerate(kept,1):
        im=download_image(item["thumbnail_url"]);q,bbox,bstd=feature(im);rr=rank(q,refs,sigs,groups)
        top=rr[0] if rr else None;second=rr[1] if len(rr)>1 else None
        score=top[1] if top else 0.;margin=score-(second[1] if second else 0.);pred=top[2][0]["dex"] if top else 0
        top5=[];[top5.append(x[2][0]["dex"]) for x in rr if x[2][0]["dex"] not in top5]
        accepted=bool(top and score>=.90 and margin>=.03)
        metrics["n"]+=1;metrics["visual_top1"]+=pred==item["target_dex"];metrics["visual_top5"]+=item["target_dex"] in top5[:5]
        metrics["visual_accept"]+=accepted;metrics["visual_accept_correct"]+=accepted and pred==item["target_dex"]
        auto=False;auto_dex=0;mode=""
        if accepted and top and len(top[2])==1:auto=True;auto_dex=pred;mode="visual"
        elif sess is not None:
            acs,aok=ai(sess,labels,im)
            if aok:
                ad=acs[0][0]
                if species_counts[ad]==1:auto=True;auto_dex=ad;mode="ai_single"
                else:
                    lr=rank(q,refs,sigs,groups,allowed=ad);la=lr[0] if lr else None;lb=lr[1] if len(lr)>1 else None
                    lm=la[1]-(lb[1] if lb else 0) if la else 0
                    if la and la[1]>=.90 and lm>=.03 and len(la[2])==1:auto=True;auto_dex=ad;mode="ai_visual"
        metrics["auto"]+=auto;metrics["auto_correct"]+=auto and auto_dex==item["target_dex"]
        exact=""
        if item["exact_group"]:
            metrics["exact_n"]+=1;exact=(top[0]==item["exact_group"] if top else False);metrics["exact_correct"]+=bool(exact)
        row={**item,"visual_pred_dex":pred,"visual_pred_name":top[2][0]["pokemon"] if top else "","visual_score":score,"visual_margin":margin,
             "visual_top5":top5[:5],"visual_accepted":accepted,"auto_registered":auto,"auto_dex":auto_dex,"auto_mode":mode,
             "species_correct":pred==item["target_dex"],"auto_correct":(auto and auto_dex==item["target_dex"]),"exact_correct":exact,
             "bbox_ratio":bbox,"border_std":bstd}
        rows.append(row)
        if i%50==0:print(f"evaluated {i}/500",flush=True)
    m={
      "n":metrics["n"],
      "species_top1":metrics["visual_top1"]/500,
      "species_top5":metrics["visual_top5"]/500,
      "visual_accept_rate":metrics["visual_accept"]/500,
      "visual_precision_when_accepted":metrics["visual_accept_correct"]/max(1,metrics["visual_accept"]),
      "full_app_auto_rate":metrics["auto"]/500,
      "full_app_auto_precision":metrics["auto_correct"]/max(1,metrics["auto"]),
      "full_app_auto_errors":metrics["auto"]-metrics["auto_correct"],
      "exact_labeled_subset":metrics["exact_n"],
      "exact_accuracy":metrics["exact_correct"]/max(1,metrics["exact_n"]),
    }
    conf=Counter((r["target_dex"],r["visual_pred_dex"]) for r in rows if not r["species_correct"])
    diag={
      "wrong_top1":sum(not r["species_correct"] for r in rows),
      "correct_in_top5_but_not_top1":sum((not r["species_correct"]) and r["target_dex"] in r["visual_top5"] for r in rows),
      "wrong_auto_registration":sum(r["auto_registered"] and not r["auto_correct"] for r in rows),
      "small_subject_misses":sum((not r["species_correct"]) and r["bbox_ratio"]<.25 for r in rows),
      "complex_background_misses":sum((not r["species_correct"]) and r["border_std"]>55 for r in rows),
      "top_confusions":[{"target":a,"pred":b,"count":n} for (a,b),n in conf.most_common(20)]
    }
    OUT.write_text(json.dumps({"metrics":m,"selection_rejects":dict(reject),"diagnosis":diag,"results":rows},ensure_ascii=False,indent=2))
    with FAIL.open("w",newline="",encoding="utf-8-sig") as f:
        cols=["target_dex","target_name","title","site","url","thumbnail_url","visual_pred_dex","visual_pred_name","visual_score","visual_margin","visual_top5","visual_accepted","auto_registered","auto_dex","auto_mode","auto_correct","exact_group","exact_correct","bbox_ratio","border_std"]
        w=csv.DictWriter(f,fieldnames=cols,extrasaction="ignore");w.writeheader()
        for r in rows:
            if not r["species_correct"] or (r["auto_registered"] and not r["auto_correct"]):
                rr=dict(r);rr["visual_top5"]=json.dumps(rr["visual_top5"]);w.writerow(rr)
    print(json.dumps({"metrics":m,"diagnosis":diag,"selection_rejects":dict(reject)},ensure_ascii=False,indent=2))

if __name__=="__main__":main()
