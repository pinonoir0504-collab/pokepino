from pathlib import Path
import json,hashlib,base64,struct,math
A=Path(__file__).resolve().parents[1]/'app/src/main/assets'
m=json.loads((A/'shape_refs_manifest_v1.json').read_text());assert m['dimensions']==384
assert hashlib.sha256((A/'dinov2_small.onnx').read_bytes()).hexdigest()==m['model_sha256']
assert hashlib.sha256((A/m['metric_asset']).read_bytes()).hexdigest()==m['metric_sha256']
metric=json.loads((A/m['metric_asset']).read_text());assert metric['dimensions']==384
for key,count in [('mean',384),('matrix',384*384)]:
 b=base64.b64decode(metric[key],validate=True);assert len(b)==4*count;assert all(math.isfinite(v) for v in struct.unpack('<'+'f'*count,b))
n=0
for f in m['files']:
 b=(A/f['path']).read_bytes();assert hashlib.sha256(b).hexdigest()==f['sha256']
 rows=json.loads(b);assert len(rows)==f['vectors']
 for r in rows:assert 1<=r[1]<=1025 and len(base64.b64decode(r[3],validate=True))==384 and r[4] in ['catalog','photo']
 n+=len(rows)
assert n==m['vectors'] and n>=1000
print('Verified',n,'shape vectors, metric and model')

k=json.loads((A/'shape_kernel_manifest.json').read_text())
assert k['dimensions']==384 and k['gamma']==20 and k['regularization']==.1
assert k['development_only'] is True
assert len(k['species'])==len(set(k['species'])) and all(1<=d<=1025 for d in k['species'])
for key,count in [('vectors',k['rows']*384),('coefficients',k['rows']*len(k['species']))]:
 data=(A/k[key]['path']).read_bytes()
 assert len(data)==count*4 and k[key]['floats']==count
 assert hashlib.sha256(data).hexdigest()==k[key]['sha256']
 assert all(math.isfinite(v[0]) for v in struct.iter_unpack('<f',data))
print('Verified fitted kernel:',k['rows'],'references,',len(k['species']),'species')

p=json.loads((A/'shape_prototypes_manifest.json').read_text())
assert p['dimensions']==384 and p['temperature']==.02 and p['development_images']>0
assert p['source_policy'].startswith('catalog references preferred')
assert sum(p['source_counts'].values())==p['development_images']
assert p['source_counts']['catalog']>0 and p['source_counts']['photo']>=0
assert len(p['species'])==len(set(p['species'])) and all(1<=d<=1025 for d in p['species'])
assert p['max_exemplars']==3
reference_species=p['reference_species']
assert len(reference_species)==p['development_images']
assert set(reference_species)==set(p['species'])
data=(A/p['vectors']['path']).read_bytes();assert len(data)==len(reference_species)*384*4
assert p['vectors']['floats']==len(reference_species)*384
assert hashlib.sha256(data).hexdigest()==p['vectors']['sha256']
values=[v[0] for v in struct.iter_unpack('<f',data)]
assert all(math.isfinite(v) for v in values)
for i in range(len(reference_species)):
 norm=math.sqrt(sum(v*v for v in values[i*384:(i+1)*384]))
 assert .99<=norm<=1.01
print('Verified',len(p['species']),'DINOv2 classes with',len(reference_species),'normalized image exemplars')

learned=json.loads((A/'shape_learned_metric.json').read_text());assert learned['dimensions']==384 and learned['development_only']
for key,count in [('mean',384),('matrix',384*384)]:
 values=struct.unpack('<'+'f'*count,base64.b64decode(learned[key],validate=True));assert all(math.isfinite(v) for v in values)
appearance=json.loads((A/'appearance_model.json').read_text());assert appearance['development_only']
if appearance['kind'] in ['logistic','pairwise']:
 assert appearance['feature_count']==len(appearance['coef'])==len(appearance['mean'])==len(appearance['scale'])
 assert all(math.isfinite(v) for v in appearance['coef']+appearance['mean'])
 assert all(v>0 and math.isfinite(v) for v in appearance['scale'])
 if appearance['kind']=='logistic':assert appearance['export_probability_max_error']<1e-7
 if appearance['kind']=='pairwise':assert 1<=appearance['candidate_top_k']<=1025 and appearance['intercept']==0
for key,file in appearance.get('gallery',{}).items():
 data=(A/file['path']).read_bytes();assert hashlib.sha256(data).hexdigest()==file['sha256'];assert len(data)==len(appearance['reference_species'])*file['dimensions']*4
print('Verified selected appearance classifier and reference galleries')
