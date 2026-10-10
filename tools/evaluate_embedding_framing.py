#!/usr/bin/env python3
"""Held-out evaluation of the Android embedding recognizer's four scan framings."""
import base64
import io
import json
import random
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np
import onnxruntime as ort
import requests
from PIL import Image, ImageOps

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "app/src/main/assets/catalog_v2.json"
EMBEDDINGS = ROOT / "app/src/main/assets/embedding_catalog_v3.json"
MODEL = ROOT / "app/src/main/assets/mobilenet_v3_small_features.onnx"
SEED = int(sys.argv[1]) if len(sys.argv) > 1 else 20261010
SAMPLE = 100
UA = "PokepinoFramingHoldout/1.0"


def fetch_image(url):
    response = requests.get(url, headers={"User-Agent": UA}, timeout=(8, 18))
    response.raise_for_status()
    image = Image.open(io.BytesIO(response.content)).convert("RGB")
    image.thumbnail((1280, 1280), Image.Resampling.LANCZOS)
    return image


def foreground_crop(src):
    im = src.convert("RGB")
    sample = im.resize((128, 128), Image.Resampling.BILINEAR)
    a = np.asarray(sample, dtype=np.float32)
    border = np.concatenate((a[0], a[-1], a[:, 0], a[:, -1]), axis=0)
    bg = np.median(border, axis=0)
    distances = np.linalg.norm(a - bg, axis=2)
    threshold = max(28.0, float(np.partition(distances.ravel(), int(distances.size * .58))[int(distances.size * .58)]))
    ys, xs = np.where(distances > threshold)
    if len(xs) < 220 or xs.max() <= xs.min() or ys.max() <= ys.min():
        return im
    x0 = max(0, int(xs.min()) - 4)
    y0 = max(0, int(ys.min()) - 4)
    x1 = min(128, int(xs.max()) + 5)
    y1 = min(128, int(ys.max()) + 5)
    sx0 = max(0, int(x0 / 128 * im.width))
    sy0 = max(0, int(y0 / 128 * im.height))
    sx1 = min(im.width, max(sx0 + 1, int(x1 / 128 * im.width)))
    sy1 = min(im.height, max(sy0 + 1, int(y1 / 128 * im.height)))
    if (sx1 - sx0) * (sy1 - sy0) < im.width * im.height / 25:
        return im
    return im.crop((sx0, sy0, sx1, sy1))


def framing(image, mode):
    if mode == 0:
        crop = foreground_crop(image)
        scale = 256.0 / max(1, min(crop.size))
        width = max(224, int(crop.width * scale + .5))
        height = max(224, int(crop.height * scale + .5))
        resized = crop.resize((width, height), Image.Resampling.BILINEAR)
        x, y = max(0, (width - 224) // 2), max(0, (height - 224) // 2)
        return resized.crop((x, y, x + 224, y + 224))
    if mode == 1:
        return ImageOps.pad(image, (224, 224), method=Image.Resampling.BILINEAR, color=(245, 245, 245), centering=(.5, .5))
    if mode == 2:
        side = min(image.size)
        x, y = (image.width - side) // 2, (image.height - side) // 2
        return image.crop((x, y, x + side, y + side)).resize((224, 224), Image.Resampling.BILINEAR)
    return ImageOps.pad(foreground_crop(image), (224, 224), method=Image.Resampling.BILINEAR, color=(245, 245, 245), centering=(.5, .5))


def embed(session, input_name, image, mode):
    square = framing(image, mode)
    a = np.asarray(square, dtype=np.float32)
    mean = np.array([.485, .456, .406], dtype=np.float32)
    std = np.array([.229, .224, .225], dtype=np.float32)
    a = (a / 255.0 - mean) / std
    out = session.run(None, {input_name: np.transpose(a, (2, 0, 1))[None].astype(np.float32)})[0]
    q = np.asarray(out, dtype=np.float32).reshape(-1)
    norm = np.linalg.norm(q)
    return q / max(float(norm), 1e-6)


def load_reference_vectors():
    rows = json.loads(EMBEDDINGS.read_text(encoding="utf-8"))
    refs = []
    for row in rows:
        try:
            dex, group = int(row[1]), str(row[2])
            raw = base64.b64decode(row[3])
            if dex <= 0 or not group or len(raw) != 576:
                continue
            vec = np.frombuffer(raw, dtype=np.int8).astype(np.float32) / 127.0
            proto = str(row[4]) if len(row) > 4 else "base"
            penalty = .012 if proto == "mirror" else (.008 if proto in ("dark", "bright") else 0)
            refs.append((dex, group, vec, float(np.linalg.norm(vec)), penalty))
        except Exception:
            continue
    if len(refs) < 1000:
        raise RuntimeError(f"embedding database too small: {len(refs)}")
    return refs


def make_candidates(catalog, refs):
    urls_by_group = defaultdict(set)
    dex_by_group = {}
    for row in catalog:
        try:
            group, dex = str(row[6]), int(row[1])
            urls = set(str(u) for u in (row[8] or []) if str(u).startswith("http"))
            if group and dex > 0:
                urls_by_group[group].update(urls)
                dex_by_group[group] = dex
        except Exception:
            continue
    ref_groups_by_dex = defaultdict(set)
    for dex, group, *_ in refs:
        ref_groups_by_dex[dex].add(group)
    targets = []
    seen_urls = set()
    for row in catalog:
        try:
            group, dex = str(row[6]), int(row[1])
            urls = [str(u) for u in (row[8] or []) if str(u).startswith("http")]
            if dex <= 0 or not group or not urls or group in seen_urls:
                continue
            if not any((r[9].get("feature", "") if isinstance(r[9], dict) else "") for r in [row]):
                continue
            # Exclude this visual group and any catalog group sharing its source URL.
            excluded = {g for g, gu in urls_by_group.items() if set(urls) & gu}
            remaining = ref_groups_by_dex[dex] - excluded
            if not remaining:
                continue
            targets.append((row, urls[0], excluded))
            seen_urls.add(group)
        except Exception:
            continue
    rng = random.Random(SEED)
    rng.shuffle(targets)
    return targets


def main():
    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    refs = load_reference_vectors()
    session = ort.InferenceSession(str(MODEL), providers=["CPUExecutionProvider"])
    input_name = session.get_inputs()[0].name
    targets = make_candidates(catalog, refs)
    results = []
    failures = 0
    for row, url, excluded_groups in targets:
        try:
            image = fetch_image(url)
            queries = [embed(session, input_name, image, mode) for mode in range(4)]
            usable = [r for r in refs if r[1] not in excluded_groups]
            grouped = {}
            for dex, group, vec, norm, penalty in usable:
                values = grouped.setdefault(dex, {}).setdefault(group, np.full(4, -np.inf, dtype=np.float32))
                for mode, q in enumerate(queries):
                    values[mode] = max(values[mode], float(np.dot(q, vec) / max(norm, 1e-6) - penalty))
            by_dex = {}
            by_pool = {k: {} for k in (1, 2, 3, 4, 5, 8, 12)}
            for dex, group_scores in grouped.items():
                matrix = np.stack(list(group_scores.values()))
                by_dex[dex] = np.max(matrix, axis=0)
                for pool_size in by_pool:
                    top_groups = np.sort(matrix, axis=0)[-min(pool_size, len(matrix)):]
                    by_pool[pool_size][dex] = np.mean(top_groups, axis=0)
            fused = {}
            strategies = {}
            production_scores = by_pool[3]
            for dex, values in production_scores.items():
                valid = sorted((float(v) for v in values if np.isfinite(v)), reverse=True)
                if not valid:
                    continue
                fused[dex] = max(float(values[0]) if np.isfinite(values[0]) else 0.0,
                                 sum(valid[:2]) / min(2, len(valid)))
            for pool_size, scores in by_pool.items():
                for policy in ("legacy_top2", "mean4", "top2_mean", "legacy_mix25", "legacy_mix50", "legacy_mix75"):
                    strategies[(pool_size, policy)] = {}
                for dex, values in scores.items():
                    valid = sorted((float(v) for v in values if np.isfinite(v)), reverse=True)
                    legacy = float(values[0]) if np.isfinite(values[0]) else 0.0
                    others = [float(v) for v in values[1:] if np.isfinite(v)]
                    strategies[(pool_size, "legacy_top2")][dex] = max(legacy, sum(valid[:2]) / min(2, len(valid)))
                    strategies[(pool_size, "mean4")][dex] = float(np.mean(valid))
                    strategies[(pool_size, "top2_mean")][dex] = sum(valid[:2]) / min(2, len(valid))
                    other_mean = float(np.mean(others)) if others else legacy
                    strategies[(pool_size, "legacy_mix25")][dex] = .25 * legacy + .75 * other_mean
                    strategies[(pool_size, "legacy_mix50")][dex] = .50 * legacy + .50 * other_mean
                    strategies[(pool_size, "legacy_mix75")][dex] = .75 * legacy + .25 * other_mean
            if not fused:
                failures += 1
                continue
            target_dex = int(row[1])
            base_top = max(by_dex, key=lambda d: by_dex[d][0])
            order = sorted(fused, key=fused.get, reverse=True)
            winners = [max(production_scores, key=lambda d: production_scores[d][m]) for m in range(4)]
            top = order[0]
            policy_correct = {}
            for key, scores in strategies.items():
                policy_correct[f"pool{key[0]}_{key[1]}"] = int(max(scores, key=scores.get) == target_dex)
            view_correct = [int(winner == target_dex) for winner in winners]
            second = fused[order[1]] if len(order) > 1 else 0.0
            margin = fused[top] - second
            accepted = winners.count(top) >= 2 and fused[top] >= .66 and margin >= .035
            results.append({
                "id": str(row[0]), "dex": target_dex,
                "baseline_top1_correct": int(base_top == target_dex),
                "ensemble_top1_correct": int(top == target_dex),
                "ensemble_top3_correct": int(target_dex in order[:3]),
                "accepted": int(accepted),
                "accepted_correct": int(accepted and top == target_dex),
                "view_correct": view_correct,
                "policy_correct": policy_correct,
            })
            if len(results) >= SAMPLE:
                break
        except Exception:
            failures += 1
        if len(results) and len(results) % 20 == 0:
            print(f"embedding holdout {len(results)}/{SAMPLE}, fetch/scan failures={failures}", flush=True)
    if len(results) < SAMPLE:
        raise RuntimeError(f"only {len(results)}/{SAMPLE} held-out photos could be evaluated")
    accepted = sum(x["accepted"] for x in results)
    report = {
        "method": "Android EmbeddingRecognizer framing implementation, mirrored in Python",
        "seed": SEED,
        "sample_count": len(results),
        "source": "catalog reference photos; held out by visualGroupId and shared source URL",
        "fetch_or_scan_failures_before_fill": failures,
        "baseline_top1_percent": 100 * sum(x["baseline_top1_correct"] for x in results) / len(results),
        "four_frame_top1_percent": 100 * sum(x["ensemble_top1_correct"] for x in results) / len(results),
        "four_frame_top3_percent": 100 * sum(x["ensemble_top3_correct"] for x in results) / len(results),
        "view_top1_percent": [100 * sum(x["view_correct"][i] for x in results) / len(results) for i in range(4)],
        "pooling_and_fusion_top1_percent": {
            key: 100 * sum(x["policy_correct"][key] for x in results) / len(results)
            for key in results[0]["policy_correct"]
        },
        "auto_accept_coverage_percent": 100 * accepted / len(results),
        "auto_accept_precision_percent": 100 * sum(x["accepted_correct"] for x in results) / max(1, accepted),
        "records": results,
        "caveat": "This is a held-out catalog-reference test, not a random marketplace-photo test or physical-device camera test."
    }
    out = ROOT / "embedding_framing_eval.json"
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: v for k, v in report.items() if k != "records"}, ensure_ascii=False, indent=2))
    if len(results) != SAMPLE:
        raise SystemExit("evaluation sample size mismatch")


if __name__ == "__main__":
    main()
