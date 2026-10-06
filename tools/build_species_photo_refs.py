#!/usr/bin/env python3
"""Regenerate species-only vectors from verified local training photographs.
Requires: numpy, onnxruntime, Pillow.
Usage: python3 tools/build_species_photo_refs.py --image-directory /path/to/images
Files must be named by Mercari listing ID, e.g. m31741443700.jpg.
Never add holdout photographs to this manifest before independent evaluation.
"""
import argparse, base64, hashlib, json
from pathlib import Path
import numpy as np
import onnxruntime as ort
from PIL import Image, ImageEnhance, ImageOps
ROOT=Path(__file__).resolve().parents[1]
ASSETS=ROOT/'app/src/main/assets'
def crop(im):
 a=np.asarray(im.resize((128,128),Image.Resampling.BILINEAR),np.float32);border=np.concatenate([a[0],a[-1],a[1:-1,0],a[1:-1,-1]]);bg=np.sort(border,axis=0)[len(border)//2];d=np.linalg.norm(a-bg,axis=2);t=max(28,float(np.sort(d.reshape(-1))[int(d.size*.58)]));ys,xs=np.where(d>t)
 if len(xs)<220 or xs.min()==xs.max() or ys.min()==ys.max():return im
 x0=max(0,int(xs.min())-4);x1=min(127,int(xs.max())+4);y0=max(0,int(ys.min())-4);y1=min(127,int(ys.max())+4)
 box=(int(x0/128*im.width),int(y0/128*im.height),int((x1+1)/128*im.width),int((y1+1)/128*im.height))
 if (box[2]-box[0])*(box[3]-box[1])<im.width*im.height//25:return im
 return im.crop(box)

def input_tensor(im):
 im=crop(im);scale=256/min(im.size)
 w,h=[max(224,int(x*scale+.5)) for x in im.size]
 im=im.resize((w,h),Image.Resampling.BILINEAR);x=(w-224)//2;y=(h-224)//2
 im=im.crop((x,y,x+224,y+224));a=np.asarray(im,np.float32)/255
 a=(a-np.array([.485,.456,.406],np.float32))/np.array([.229,.224,.225],np.float32)
 return np.transpose(a,(2,0,1))[None].copy()
def main():
 ap=argparse.ArgumentParser();ap.add_argument('--image-directory',type=Path,required=True);args=ap.parse_args()
 manifest=json.loads((ROOT/'species_photo_refs_manifest.json').read_text());opts=ort.SessionOptions();opts.intra_op_num_threads=2
 model=ort.InferenceSession(str(ASSETS/'mobilenet_v3_small_features.onnx'),opts);rows=[]
 for source in manifest['sources']:
  path=args.image_directory/(source['item_id']+'.jpg')
  if hashlib.sha256(path.read_bytes()).hexdigest()!=source['image_sha256']:raise ValueError('training source hash mismatch: '+source['item_id'])
  im=Image.open(path).convert('RGB');im.thumbnail((1280,1280),Image.Resampling.BILINEAR)
  views={'base':im,'mirror':ImageOps.mirror(im),'dark':ImageEnhance.Brightness(im).enhance(.82),'bright':ImageEnhance.Brightness(im).enhance(1.12)}
  for kind,img in views.items():
   vec=model.run(None,{model.get_inputs()[0].name:input_tensor(img)})[0].reshape(-1);vec/=np.linalg.norm(vec)
   raw=np.clip(np.rint(vec*127),-127,127).astype(np.int8)
   rows.append([source['item_id']+'-'+kind,source['dex'],'',base64.b64encode(raw.tobytes()).decode(),kind])
 target=ASSETS/'species_photo_refs_v1.json';target.write_text(json.dumps(rows,ensure_ascii=False,separators=(',',':')))
 print(f'{len(manifest["sources"])} verified sources, {len(rows)} species-only prototypes')
if __name__=='__main__':main()
