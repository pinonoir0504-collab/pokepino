#!/usr/bin/env python3
import base64, io, json, math, time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

import numpy as np
import requests
import torch
import torch.nn as nn
from PIL import Image, ImageEnhance, ImageOps
from torchvision.models import mobilenet_v3_small, MobileNet_V3_Small_Weights

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app" / "src" / "main" / "assets"
CATALOG = ASSETS / "catalog_v2.json"
MODEL_OUT = ASSETS / "mobilenet_v3_small_features.onnx"
EMBED_OUT = ASSETS / "embedding_catalog_v3.json"
STATS_OUT = ROOT / "recognition_assets_stats.json"
UA = "PokepinoRecognitionBuilder/3.0"
EMBEDDING_SIZE = 576

session = requests.Session()
session.headers.update({"User-Agent": UA, "Accept-Language": "ja,en;q=0.7"})


def get_image(url, timeout=30):
    last = None
    for n in range(3):
        try:
            r = session.get(url, timeout=timeout)
            r.raise_for_status()
            if len(r.content) > 8_000_000:
                raise ValueError("image too large")
            im = Image.open(io.BytesIO(r.content)).convert("RGB")
            if min(im.size) < 48:
                raise ValueError("image too small")
            return im
        except Exception as e:
            last = e
            time.sleep(0.4 * (n + 1))
    raise last or RuntimeError(url)


def foreground_crop(im):
    arr = np.asarray(im, dtype=np.float32)
    h, w, _ = arr.shape
    if h < 12 or w < 12:
        return im
    border = np.concatenate([arr[0], arr[-1], arr[:, 0], arr[:, -1]], axis=0)
    bg = np.median(border, axis=0)
    dist = np.linalg.norm(arr - bg, axis=2)
    threshold = max(28.0, float(np.percentile(dist, 58)))
    ys, xs = np.where(dist > threshold)
    if len(xs) > max(20, h * w * .015):
        x0, x1 = max(0, int(xs.min()) - 4), min(w, int(xs.max()) + 5)
        y0, y1 = max(0, int(ys.min()) - 4), min(h, int(ys.max()) + 5)
        if (x1 - x0) * (y1 - y0) > .04 * w * h:
            return im.crop((x0, y0, x1, y1))
    return im


def variants(im):
    base = foreground_crop(im)
    yield "base", base
    yield "mirror", ImageOps.mirror(base)
    yield "dark", ImageEnhance.Contrast(ImageEnhance.Brightness(base).enhance(.82)).enhance(1.05)
    yield "bright", ImageEnhance.Contrast(ImageEnhance.Brightness(base).enhance(1.12)).enhance(.96)


class FeatureModel(nn.Module):
    def __init__(self):
        super().__init__()
        m = mobilenet_v3_small(weights=MobileNet_V3_Small_Weights.DEFAULT)
        self.features = m.features
        self.avgpool = m.avgpool

    def forward(self, x):
        x = self.features(x)
        x = self.avgpool(x)
        return torch.flatten(x, 1)


def build_model():
    model = FeatureModel().eval()
    dummy = torch.zeros(1, 3, 224, 224)
    with torch.inference_mode():
        z = model(dummy)
    if tuple(z.shape) != (1, EMBEDDING_SIZE):
        raise RuntimeError(f"unexpected embedding shape {tuple(z.shape)}")
    torch.onnx.export(
        model, dummy, str(MODEL_OUT),
        input_names=["input"], output_names=["embedding"],
        dynamic_axes={"input": {0: "batch"}, "embedding": {0: "batch"}},
        opset_version=17, do_constant_folding=True
    )
    if MODEL_OUT.stat().st_size < 4_000_000:
        raise RuntimeError("exported model looks too small")
    return model


def transform(im):
    im = im.convert("RGB")
    w, h = im.size
    short = max(1, min(w, h))
    scale = 256.0 / short
    rw, rh = max(224, round(w * scale)), max(224, round(h * scale))
    im = im.resize((rw, rh), Image.Resampling.BILINEAR)
    x0, y0 = max(0, (rw - 224) // 2), max(0, (rh - 224) // 2)
    im = im.crop((x0, y0, x0 + 224, y0 + 224))
    a = np.asarray(im, dtype=np.float32) / 255.0
    a = (a - np.array([.485, .456, .406], dtype=np.float32)) / np.array([.229, .224, .225], dtype=np.float32)
    return torch.from_numpy(np.transpose(a, (2, 0, 1))).float()


def quantize(v):
    v = np.asarray(v, dtype=np.float32)
    n = float(np.linalg.norm(v))
    if not math.isfinite(n) or n <= 1e-8:
        raise ValueError("zero embedding")
    v = v / n
    q = np.clip(np.rint(v * 127.0), -127, 127).astype(np.int8)
    return base64.b64encode(q.tobytes()).decode("ascii")


def main():
    rows = json.loads(CATALOG.read_text(encoding="utf-8"))
    refs = []
    seen = set()
    for r in rows:
        if not isinstance(r, list) or len(r) < 9:
            continue
        rid, dex, group = str(r[0]), int(r[1]), str(r[6])
        urls = r[8] if isinstance(r[8], list) else []
        for u in urls[:4]:
            if dex <= 0 or not isinstance(u, str) or not u.startswith("http"):
                continue
            key = (rid, u)
            if key in seen:
                continue
            seen.add(key)
            refs.append({"id": rid, "dex": dex, "group": group, "url": u})

    if len(refs) < 1000:
        raise SystemExit(f"too few reference images: {len(refs)}")

    model = build_model()
    downloaded = {}
    failures = []

    def one(url):
        try:
            return url, get_image(url)
        except Exception as e:
            return url, e

    unique_urls = list(dict.fromkeys(x["url"] for x in refs))
    with ThreadPoolExecutor(max_workers=20) as ex:
        futures = [ex.submit(one, u) for u in unique_urls]
        for i, fut in enumerate(as_completed(futures), 1):
            u, value = fut.result()
            if isinstance(value, Exception):
                failures.append([u, str(value)])
            else:
                downloaded[u] = value
            if i % 200 == 0:
                print(f"download {i}/{len(unique_urls)} ok={len(downloaded)}", flush=True)

    output = []
    batch_tensors = []
    batch_meta = []

    def flush():
        nonlocal batch_tensors, batch_meta
        if not batch_tensors:
            return
        batch = torch.stack(batch_tensors)
        with torch.inference_mode():
            z = model(batch)
            z = torch.nn.functional.normalize(z, dim=1).cpu().numpy()
        for meta, vec in zip(batch_meta, z):
            output.append([meta["id"], meta["dex"], meta["group"], quantize(vec)])
        batch_tensors = []
        batch_meta = []

    for i, item in enumerate(refs, 1):
        image = downloaded.get(item["url"])
        if image is None:
            continue
        made = []
        try:
            made = list(variants(image))
            for _, vim in made:
                batch_tensors.append(transform(vim))
                batch_meta.append(item)
                if len(batch_tensors) >= 64:
                    flush()
        finally:
            for _, vim in made:
                if vim is not image:
                    try:
                        vim.close()
                    except Exception:
                        pass
        if i % 250 == 0:
            print(f"embed {i}/{len(refs)} prototypes={len(output) + len(batch_tensors)}", flush=True)
    flush()

    output.sort(key=lambda x: (x[1], x[2], x[0], x[3]))
    EMBED_OUT.write_text(json.dumps(output, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")

    species = {int(x[1]) for x in output if int(x[1]) > 0}
    groups = {str(x[2]) for x in output if str(x[2])}
    stats = {
        "catalog_records": len(rows),
        "reference_images_requested": len(refs),
        "reference_images_downloaded": len(downloaded),
        "download_failures": len(failures),
        "embedding_prototypes": len(output),
        "embedding_species": len(species),
        "embedding_groups": len(groups),
        "prototypes_per_reference_target": 4,
        "model_bytes": MODEL_OUT.stat().st_size,
        "database_bytes": EMBED_OUT.stat().st_size,
        "sample_failures": failures[:20],
    }
    STATS_OUT.write_text(json.dumps(stats, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(stats, ensure_ascii=False, indent=2))

    if len(output) < 3500:
        raise SystemExit("too few embedding prototypes")
    if len(species) < 800:
        raise SystemExit("embedding species coverage too small")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
