#!/usr/bin/env python3
import argparse, base64, io, json, math, os, random, sys, tempfile
from collections import defaultdict
from pathlib import Path

import numpy as np
import requests
from PIL import Image, ImageEnhance, ImageOps

ROOT=Path(__file__).resolve().parents[1]
CAT=ROOT/"app"/"src"/"main"/"assets"/"catalog_v2.json"
REV="68c055b397ad1a9bc61bbba39be438c4a2ac3f28"
BASE=f"https://huggingface.co/BiernyVR/pokemon-classifier-mobilenetv3/resolve/{REV}"
MODEL_URL=f"{BASE}/pokemon_classifier.onnx?download=true"
LABELS_URL=f"{BASE}/pokemon_labels.json?download=true"
UA="PokepinoRuntimeValidation/1.0"

s=requests.Session()
s.headers.update({"User-Agent":UA})

def get(url, timeout=60):
    r=s.get(url,timeout=timeout)
    r.raise_for_status()
    return r

def decode_sig(encoded):
    raw=base64.b64decode(encoded)
    if len(raw)!=264:
        raise ValueError(len(raw))
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
    if len(xs)>max(20,h*w*.015):
        x0,x1=max(0,int(xs.min())-2),min(w,int(xs.max())+3)
        y0,y1=max(0,int(ys.min())-2),min(h,int(ys.max())+3)
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
    return blocks,np.asarray(hist,dtype=np.float32),edge

def cos(a,b):
    den=float(np.linalg.norm(a)*np.linalg.norm(b))
    return float(np.dot(a,b)/den) if den else 0.0

def sim(a,b):
    ab,ah,ae=a; bb,bh,be=b
    diff=np.bitwise_xor(ae,be)
    equal=256-sum(int(x).bit_count() for x in diff.tolist())
    return .55*cos(ab,bb)+.25*cos(ah,bh)+.20*(equal/256.0)

def variants(im):
    im=im.convert("RGB")
    yield "original",im
    yield "brightness_jpeg",jpeg(ImageEnhance.Brightness(im).enhance(.82),72)
    yield "rotated",ImageOps.pad(im.rotate(5,resample=Image.Resampling.BICUBIC,expand=True,fillcolor=(245,245,245)),im.size,color=(245,245,245))
    canvas=Image.new("RGB",(int(im.width*1.25),int(im.height*1.25)),(232,232,232))
    canvas.paste(im,((canvas.width-im.width)//2,(canvas.height-im.height)//2))
    yield "padded",canvas

def jpeg(im,q):
    b=io.BytesIO()
    im.save(b,"JPEG",quality=q,optimize=True)
    b.seek(0)
    return Image.open(b).convert("RGB")

def pick_records(rows,n):
    by=defaultdict(list)
    for r in rows:
        dex=int(r[1])
        if dex>0 and r[8] and r[9].get("feature"):
            by[dex].append(r)
    rng=random.Random(20261005)
    species=list(by)
    rng.shuffle(species)
    out=[]
    # broad species coverage first
    for dex in species:
        out.append(rng.choice(by[dex]))
        if len(out)>=n: break
    # add second variants for hard multi-variant species
    if len(out)<n:
        pool=[r for rs in by.values() if len(rs)>1 for r in rs]
        rng.shuffle(pool)
        seen={r[0] for r in out}
        for r in pool:
            if r[0] not in seen:
                out.append(r);seen.add(r[0])
                if len(out)>=n: break
    return out

def softmax_scores(logits):
    z=logits-np.max(logits)
    e=np.exp(z)
    return e/e.sum()

def load_model(tmp):
    import onnxruntime as ort
    model=tmp/"pokemon_classifier.onnx"
    labels=tmp/"pokemon_labels.json"
    if not model.exists():
        model.write_bytes(get(MODEL_URL,180).content)
    if not labels.exists():
        labels.write_bytes(get(LABELS_URL,60).content)
    lab=json.loads(labels.read_text())
    ids=[int(x.get("id",i+1)) for i,x in enumerate(lab)]
    sess=ort.InferenceSession(str(model),providers=["CPUExecutionProvider"])
    return sess,ids

def model_input(im):
    im=im.convert("RGB").resize((224,224),Image.Resampling.BILINEAR)
    a=np.asarray(im,dtype=np.float32)/255.0
    mean=np.array([.485,.456,.406],dtype=np.float32)
    std=np.array([.229,.224,.225],dtype=np.float32)
    a=(a-mean)/std
    return np.transpose(a,(2,0,1))[None,:,:,:].astype(np.float32)

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("--sample",type=int,default=180)
    ap.add_argument("--model-sample",type=int,default=120)
    args=ap.parse_args()
    rows=json.loads(CAT.read_text(encoding="utf-8"))
    refs=[]
    sigs=[]
    groups=[]
    dexes=[]
    for r in rows:
        feat=r[9].get("feature","")
        if feat:
            refs.append(r)
            sigs.append(decode_sig(feat))
            groups.append(r[6])
            dexes.append(int(r[1]))
    selected=pick_records(rows,args.sample)
    if len(selected)<min(args.sample,100):
        raise SystemExit("too few evaluation records")

    exact_total=0; exact_group=0; aug_total=0; aug_group=0; aug_species=0; aug_top5_group=0
    visual_accept=0; visual_accept_correct=0
    visual_accept_loose=0; visual_accept_loose_correct=0
    fetch_fail=0
    model_items=[]
    for idx,r in enumerate(selected,1):
        try:
            data=get(r[8][0],45).content
            im=Image.open(io.BytesIO(data)).convert("RGB")
        except Exception:
            fetch_fail+=1
            continue
        if len(model_items)<args.model_sample:
            model_items.append((r,im.copy()))
        for name,v in variants(im):
            q=feature(v)
            scores=np.fromiter((sim(q,x) for x in sigs),dtype=np.float32,count=len(sigs))
            order=np.argsort(scores)[::-1]
            target_group=r[6]; target_dex=int(r[1])
            if name=="original":
                exact_total+=1
                if groups[int(order[0])]==target_group: exact_group+=1
            else:
                aug_total+=1
                top=int(order[0])
                top_score=float(scores[top])
                second_score=float(scores[int(order[1])]) if len(order)>1 else 0.0
                margin=top_score-second_score
                correct=(groups[top]==target_group)
                if correct: aug_group+=1
                if dexes[top]==target_dex: aug_species+=1
                if target_group in [groups[int(x)] for x in order[:5]]: aug_top5_group+=1
                if top_score>=.92 and margin>=.045:
                    visual_accept+=1
                    if correct: visual_accept_correct+=1
                if top_score>=.90 and margin>=.03:
                    visual_accept_loose+=1
                    if correct: visual_accept_loose_correct+=1
        if idx%25==0:
            print(f"visual eval {idx}/{len(selected)} fetch_fail={fetch_fail}",flush=True)

    model_top1=model_top5=model_accept=model_accept_correct=0
    model_total=0
    model_error=""
    try:
        import onnxruntime as ort  # noqa
        with tempfile.TemporaryDirectory() as td:
            sess,labels=load_model(Path(td))
            input_name=sess.get_inputs()[0].name
            for i,(r,im) in enumerate(model_items,1):
                target=int(r[1])
                out=sess.run(None,{input_name:model_input(im)})[0]
                logits=np.asarray(out).reshape(-1)
                probs=softmax_scores(logits)
                order=np.argsort(probs)[::-1][:5]
                pred_ids=[labels[int(x)] if int(x)<len(labels) else int(x)+1 for x in order]
                model_total+=1
                if pred_ids[0]==target:model_top1+=1
                if target in pred_ids:model_top5+=1
                margin=float(probs[order[0]]-(probs[order[1]] if len(order)>1 else 0))
                accepted=float(probs[order[0]])>=.35 and margin>=.10
                if accepted:
                    model_accept+=1
                    if pred_ids[0]==target:model_accept_correct+=1
                if i%25==0:
                    print(f"model eval {i}/{len(model_items)}",flush=True)
    except Exception as e:
        model_error=repr(e)

    result={
        "catalog_records":len(rows),
        "catalog_signatures":len(sigs),
        "evaluation_records_requested":len(selected),
        "fetch_failures":fetch_fail,
        "visual_exact_group_top1": exact_group/max(1,exact_total),
        "visual_augmented_group_top1": aug_group/max(1,aug_total),
        "visual_augmented_group_top5": aug_top5_group/max(1,aug_total),
        "visual_augmented_species_top1": aug_species/max(1,aug_total),
        "visual_augmented_queries":aug_total,
        "visual_accept_rate_strict":visual_accept/max(1,aug_total),
        "visual_precision_when_accepted_strict":visual_accept_correct/max(1,visual_accept),
        "visual_accept_rate_loose":visual_accept_loose/max(1,aug_total),
        "visual_precision_when_accepted_loose":visual_accept_loose_correct/max(1,visual_accept_loose),
        "model_total":model_total,
        "model_species_top1":model_top1/max(1,model_total),
        "model_species_top5":model_top5/max(1,model_total),
        "model_accept_rate":model_accept/max(1,model_total),
        "model_precision_when_accepted":model_accept_correct/max(1,model_accept),
        "model_error":model_error,
    }
    print(json.dumps(result,ensure_ascii=False,indent=2))
    (ROOT/"recognition_eval.json").write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding="utf-8")
    if exact_total<80:
        raise SystemExit("too few images were fetched for meaningful validation")
    if result["visual_exact_group_top1"]<.98:
        raise SystemExit("exact reference matching regressed")
    return 0

if __name__=="__main__":
    raise SystemExit(main())
