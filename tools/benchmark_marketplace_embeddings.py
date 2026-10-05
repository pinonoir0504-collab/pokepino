#!/usr/bin/env python3
import io,json,math,hashlib,time
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor,as_completed
from pathlib import Path
import numpy as np
import requests
from PIL import Image
import torch
import torch.nn as nn
from torchvision.models import mobilenet_v3_small, MobileNet_V3_Small_Weights, resnet18, ResNet18_Weights

ROOT=Path(__file__).resolve().parents[1]
ASSETS=ROOT/"app"/"src"/"main"/"assets"
AUDIT=ROOT/"marketplace500_audit.json"
OUT=ROOT/"embedding_benchmark.json"
UA="PokepinoEmbeddingBenchmark/1.0"
s=requests.Session();s.headers.update({"User-Agent":UA})

def get_image(url):
    r=s.get(url,timeout=25);r.raise_for_status()
    if len(r.content)>8_000_000:raise ValueError("too large")
    im=Image.open(io.BytesIO(r.content)).convert("RGB")
    if min(im.size)<100:raise ValueError("small")
    return im

def foreground_crop(im):
    arr=np.asarray(im,dtype=np.float32)
    h,w,_=arr.shape
    border=np.concatenate([arr[0],arr[-1],arr[:,0],arr[:,-1]],axis=0)
    bg=np.median(border,axis=0)
    dist=np.linalg.norm(arr-bg,axis=2)
    mask=dist>max(28.,float(np.percentile(dist,58)))
    ys,xs=np.where(mask)
    if len(xs)>max(20,h*w*.015):
        x0,x1=max(0,int(xs.min())-3),min(w,int(xs.max())+4)
        y0,y1=max(0,int(ys.min())-3),min(h,int(ys.max())+4)
        # Avoid pathological crops.
        if (x1-x0)*(y1-y0)>.04*w*h:
            return im.crop((x0,y0,x1,y1))
    return im

def download_all(items,key,urlkey,workers=24):
    out={};fails=[]
    def one(x):
        try:return x[key],foreground_crop(get_image(x[urlkey]))
        except Exception as e:return x[key],e
    with ThreadPoolExecutor(max_workers=workers) as ex:
        fut=[ex.submit(one,x) for x in items]
        for i,f in enumerate(as_completed(fut),1):
            k,v=f.result()
            if isinstance(v,Exception):fails.append((k,str(v)))
            else:out[k]=v
            if i%200==0:print(f"downloaded {i}/{len(items)} ok={len(out)}",flush=True)
    return out,fails

def ref_records():
    rows=json.loads((ASSETS/"catalog_v2.json").read_text())
    seen=set();out=[]
    for r in rows:
        if int(r[1])<=0 or not r[8]:continue
        u=r[8][0]
        key=hashlib.sha1(u.encode()).hexdigest()
        if key in seen:continue
        seen.add(key)
        out.append({"key":key,"url":u,"dex":int(r[1]),"group":str(r[6]),"id":str(r[0])})
    return out

def embed_images(images,kind):
    if kind=="mobilenet_v3_small":
        weights=MobileNet_V3_Small_Weights.DEFAULT
        model=mobilenet_v3_small(weights=weights)
        model.classifier=nn.Identity()
    elif kind=="resnet18":
        weights=ResNet18_Weights.DEFAULT
        model=resnet18(weights=weights)
        model.fc=nn.Identity()
    else:raise ValueError(kind)
    model.eval()
    tfm=weights.transforms()
    keys=list(images)
    result={}
    with torch.inference_mode():
        for st in range(0,len(keys),32):
            ks=keys[st:st+32]
            batch=torch.stack([tfm(images[k]) for k in ks])
            z=model(batch)
            z=torch.nn.functional.normalize(z,dim=1)
            for k,v in zip(ks,z.cpu().numpy()):result[k]=v.astype(np.float32)
            if (st//32)%10==0:print(f"{kind} embeddings {min(st+32,len(keys))}/{len(keys)}",flush=True)
    del model
    return result

def evaluate_embedding(kind,qrows,qimgs,refs,rimgs):
    allimgs={**{f"q:{k}":v for k,v in qimgs.items()},**{f"r:{k}":v for k,v in rimgs.items()}}
    emb=embed_images(allimgs,kind)
    usable_refs=[r for r in refs if f"r:{r['key']}" in emb]
    R=np.stack([emb[f"r:{r['key']}"] for r in usable_refs])
    ref_dex=np.array([r["dex"] for r in usable_refs],dtype=np.int32)
    ref_group=[r["group"] for r in usable_refs]
    species=sorted(set(ref_dex.tolist()))
    idx_by_species={d:np.where(ref_dex==d)[0] for d in species}
    top1=top5=exact_n=exact_ok=0;n=0
    scores_dump=[]
    for row in qrows:
        qk=row["qkey"]
        if f"q:{qk}" not in emb:continue
        q=emb[f"q:{qk}"]
        sims=R@q
        sp=[(d,float(sims[ix].max())) for d,ix in idx_by_species.items()]
        sp.sort(key=lambda x:x[1],reverse=True)
        pred=sp[0][0]; tops=[d for d,_ in sp[:5]]
        n+=1;top1+=pred==row["target_dex"];top5+=row["target_dex"] in tops
        best_i=int(np.argmax(sims));pred_group=ref_group[best_i]
        if row.get("exact_group"):
            exact_n+=1;exact_ok+=pred_group==row["exact_group"]
        scores_dump.append({
            "qkey":qk,"target":row["target_dex"],"pred":pred,"top5":tops,
            "top_score":sp[0][1],"margin":sp[0][1]-sp[1][1],
            "pred_group":pred_group
        })
    return {
        "n":n,"species_top1":top1/max(1,n),"species_top5":top5/max(1,n),
        "exact_n":exact_n,"exact_accuracy":exact_ok/max(1,exact_n),
        "scores":scores_dump
    }

def generic_ai(qrows,qimgs):
    # Reuse the same model and preprocessing as the Android app.
    import sys
    sys.path.insert(0,str(ROOT/"tools"))
    import audit_marketplace_500 as a
    sess,labels=a.load_ai()
    n=top1=top5=accept=accept_ok=0;out=[]
    for i,row in enumerate(qrows,1):
        im=qimgs.get(row["qkey"])
        if im is None:continue
        cs,ok=a.ai(sess,labels,im)
        pred=cs[0][0];tops=[x[0] for x in cs]
        n+=1;top1+=pred==row["target_dex"];top5+=row["target_dex"] in tops
        accept+=ok;accept_ok+=ok and pred==row["target_dex"]
        out.append({"qkey":row["qkey"],"target":row["target_dex"],"pred":pred,"top5":tops,"prob":cs[0][1],"accepted":ok})
        if i%100==0:print(f"generic AI {i}/{len(qrows)}",flush=True)
    return {"n":n,"species_top1":top1/max(1,n),"species_top5":top5/max(1,n),
            "accept_rate":accept/max(1,n),"precision_when_accepted":accept_ok/max(1,accept),"scores":out}

def fusion(qrows,embres,aires):
    em={x["qkey"]:x for x in embres["scores"]}
    ai={x["qkey"]:x for x in aires["scores"]}
    # Tune a simple confidence gate on deterministic first half; report second half.
    rows=sorted(qrows,key=lambda r:r["qkey"])
    tune=rows[:len(rows)//2];test=rows[len(rows)//2:]
    choices=[]
    for threshold in [0,.10,.15,.20,.25,.30,.35,.40,.50,.60]:
        def predict(r):
            a=ai.get(r["qkey"]);e=em.get(r["qkey"])
            if not a:return e["pred"] if e else 0
            if a["prob"]>=threshold:return a["pred"]
            return e["pred"] if e else a["pred"]
        acc=sum(predict(r)==r["target_dex"] for r in tune)/max(1,len(tune))
        choices.append((acc,threshold))
    _,thr=max(choices)
    def calc(rs):
        n=ok=0
        for r in rs:
            a=ai.get(r["qkey"]);e=em.get(r["qkey"])
            if not a and not e:continue
            pred=(a["pred"] if a and a["prob"]>=thr else (e["pred"] if e else a["pred"]))
            n+=1;ok+=pred==r["target_dex"]
        return ok/max(1,n)
    return {"gate_ai_probability":thr,"tune_top1":calc(tune),"validation_top1":calc(test),"all_top1":calc(rows)}

def main():
    audit=json.loads(AUDIT.read_text())
    qrows=[]
    for i,r in enumerate(audit["results"]):
        qrows.append({"qkey":f"{i:04d}","url":r["thumbnail_url"],"target_dex":int(r["target_dex"]),"exact_group":r.get("exact_group","")})
    refs=ref_records()
    qitems=[{"key":r["qkey"],"url":r["url"]} for r in qrows]
    qimgs,qfails=download_all(qitems,"key","url")
    rimgs,rfails=download_all(refs,"key","url")
    print(f"queries ok={len(qimgs)}/{len(qrows)} refs ok={len(rimgs)}/{len(refs)}",flush=True)
    result={"query_download_failures":qfails[:30],"reference_download_failures":rfails[:30],
            "queries_ok":len(qimgs),"references_ok":len(rimgs)}
    ai=generic_ai(qrows,qimgs);result["generic_pokemon_ai"]=ai
    for kind in ["mobilenet_v3_small","resnet18"]:
        er=evaluate_embedding(kind,qrows,qimgs,refs,rimgs)
        result[kind]=er
        result[f"fusion_{kind}"]=fusion(qrows,er,ai)
    # Strip per-query score dumps from final summary artifact to keep it small.
    summary={}
    for k,v in result.items():
        if isinstance(v,dict) and "scores" in v:
            summary[k]={kk:vv for kk,vv in v.items() if kk!="scores"}
        else:summary[k]=v
    OUT.write_text(json.dumps(summary,ensure_ascii=False,indent=2))
    print(json.dumps(summary,ensure_ascii=False,indent=2))

if __name__=="__main__":main()
