#!/usr/bin/env python3
import argparse, base64, hashlib, io, json, sys, time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

import numpy as np
import requests
from PIL import Image
import torch
import torch.nn as nn
from torchvision.models import mobilenet_v3_small, MobileNet_V3_Small_Weights

ROOT=Path(__file__).resolve().parents[1]
ASSETS=ROOT/"app"/"src"/"main"/"assets"
CATALOG=ASSETS/"catalog_v2.json"
OUT=ASSETS/"embedding_catalog_v3.json"
MODEL_OUT=ASSETS/"mobilenet_v3_small_features.onnx"
STATS=ROOT/"embedding_catalog_stats.json"
UA="PokepinoEmbeddingBuilder/1.0"

session=requests.Session()
session.headers.update({"User-Agent":UA})

def get_image(url):
    last=None
    for attempt in range(3):
        try:
            r=session.get(url,timeout=30)
            r.raise_for_status()
            if len(r.content)>8_000_000:
                raise ValueError("image too large")
            im=Image.open(io.BytesIO(r.content)).convert("RGB")
            if min(im.size)<32:
                raise ValueError("image too small")
            return im
        except Exception as e:
            last=e
            time.sleep(.5*(attempt+1))
    raise last

def foreground_crop(im):
    arr=np.asarray(im,dtype=np.float32)
    h,w,_=arr.shape
    if h<16 or w<16:return im
    border=np.concatenate([arr[0],arr[-1],arr[:,0],arr[:,-1]],axis=0)
    bg=np.median(border,axis=0)
    dist=np.linalg.norm(arr-bg,axis=2)
    mask=dist>max(28.,float(np.percentile(dist,58)))
    ys,xs=np.where(mask)
    if len(xs)>max(20,h*w*.015):
        x0,x1=max(0,int(xs.min())-3),min(w,int(xs.max())+4)
        y0,y1=max(0,int(ys.min())-3),min(h,int(ys.max())+4)
        if (x1-x0)*(y1-y0)>.04*w*h:
            return im.crop((x0,y0,x1,y1))
    return im

def export_model():
    weights=MobileNet_V3_Small_Weights.DEFAULT
    model=mobilenet_v3_small(weights=weights)
    model.classifier=nn.Identity()
    model.eval()
    dummy=torch.zeros(1,3,224,224,dtype=torch.float32)
    torch.onnx.export(
        model,dummy,str(MODEL_OUT),
        input_names=["input"],output_names=["embedding"],
        opset_version=17,dynamic_axes=None,
        do_constant_folding=True
    )
    if MODEL_OUT.stat().st_size<4_000_000:
        raise RuntimeError("exported ONNX model is unexpectedly small")
    return weights,model

def all_records():
    rows=json.loads(CATALOG.read_text(encoding="utf-8"))
    out=[]
    for r in rows:
        if not isinstance(r,list) or len(r)<10:continue
        dex=int(r[1])
        refs=[u for u in (r[8] or []) if isinstance(u,str) and u.startswith("http")]
        if dex<=0 or not refs:continue
        out.append({
            "id":str(r[0]),"dex":dex,"group":str(r[6]),
            "refs":refs[:4]
        })
    return out

def download_refs(records,workers):
    jobs=[]
    for r in records:
        for idx,url in enumerate(r["refs"]):
            key=hashlib.sha1(url.encode()).hexdigest()
            jobs.append((r,idx,url,key))
    unique={}
    for job in jobs:unique.setdefault(job[3],job)
    images={};fail=[]
    def one(job):
        r,idx,url,key=job
        try:return key,foreground_crop(get_image(url)),None
        except Exception as e:return key,None,str(e)
    with ThreadPoolExecutor(max_workers=workers) as ex:
        fut=[ex.submit(one,j) for j in unique.values()]
        for i,f in enumerate(as_completed(fut),1):
            key,im,err=f.result()
            if im is not None:images[key]=im
            else:fail.append((key,err))
            if i%200==0:print(f"download {i}/{len(unique)} ok={len(images)}",flush=True)
    return jobs,images,fail

def embed_all(images,weights,model,batch_size=32):
    tfm=weights.transforms()
    keys=list(images)
    result={}
    with torch.inference_mode():
        for st in range(0,len(keys),batch_size):
            ks=keys[st:st+batch_size]
            batch=torch.stack([tfm(images[k]) for k in ks])
            z=model(batch)
            z=torch.nn.functional.normalize(z,dim=1)
            arr=z.cpu().numpy().astype(np.float32)
            for k,v in zip(ks,arr):result[k]=v
            if st%320==0:print(f"embed {min(st+batch_size,len(keys))}/{len(keys)}",flush=True)
    return result

def quantize(v):
    v=np.asarray(v,dtype=np.float32)
    v=v/max(1e-9,float(np.linalg.norm(v)))
    q=np.clip(np.rint(v*127),-127,127).astype(np.int8)
    return base64.b64encode(q.tobytes()).decode("ascii")

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("--workers",type=int,default=24)
    args=ap.parse_args()

    records=all_records()
    if len(records)<1000:
        print(f"too few catalog records: {len(records)}",file=sys.stderr);return 2
    weights,model=export_model()
    jobs,images,fail=download_refs(records,args.workers)
    if len(images)<1000:
        print(f"too few reference images downloaded: {len(images)}",file=sys.stderr);return 2
    embeddings=embed_all(images,weights,model)

    rows=[]
    per_record={}
    for r,idx,url,key in jobs:
        v=embeddings.get(key)
        if v is None:continue
        row=[r["id"],r["dex"],r["group"],quantize(v),idx]
        rows.append(row)
        per_record.setdefault(r["id"],0)
        per_record[r["id"]]+=1

    # Deduplicate identical id/group/ref-slot rows while preserving multiple viewpoints.
    dedup={}
    for row in rows:
        dedup[(row[0],row[2],row[4])]=row
    rows=list(dedup.values())
    OUT.write_text(json.dumps(rows,ensure_ascii=False,separators=(",",":")),encoding="utf-8")

    by_dex={}
    by_group={}
    for row in rows:
        by_dex[row[1]]=by_dex.get(row[1],0)+1
        by_group[row[2]]=by_group.get(row[2],0)+1
    stats={
        "catalog_records":len(records),
        "embedding_rows":len(rows),
        "species":len(by_dex),
        "visual_groups":len(by_group),
        "records_with_multiple_refs":sum(1 for n in per_record.values() if n>1),
        "download_failures":len(fail),
        "model_bytes":MODEL_OUT.stat().st_size,
        "embedding_dim":576,
    }
    STATS.write_text(json.dumps(stats,ensure_ascii=False,indent=2),encoding="utf-8")
    print(json.dumps(stats,ensure_ascii=False,indent=2))
    if len(rows)<1000 or len(by_dex)<800:
        print("embedding database coverage too small",file=sys.stderr);return 2
    return 0

if __name__=="__main__":
    raise SystemExit(main())
