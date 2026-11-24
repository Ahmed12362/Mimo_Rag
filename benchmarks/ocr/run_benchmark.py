"""Runs one OCR configuration over the corpus and writes per-page metrics (M2 slice 2a).

One configuration per process, so peak memory is measured per configuration.
Usage: python run_benchmark.py --corpus /data/corpus --out /data/results --name ar_mobile_300 \
           --det PP-OCRv5_mobile_det --rec arabic_PP-OCRv5_mobile_rec --dpi 300 [--scripts ar,mixed,en]
"""
import argparse
import json
import re
import resource
import statistics
import time
import unicodedata
from pathlib import Path

import numpy as np
import pypdfium2 as pdfium
from rapidfuzz.distance import Levenshtein

ARABIC = re.compile(r"[؀-ۿݐ-ݿࢠ-ࣿﭐ-﷿ﹰ-﻿]")
LATIN = re.compile(r"[A-Za-z]")
LTR_RUN = re.compile(r"[A-Za-z0-9٠-٩](?:[A-Za-z0-9٠-٩@._\-/:]*[A-Za-z0-9٠-٩])?")
DIACRITICS = re.compile(r"[ً-ٰٟ]")
MIRROR = str.maketrans("()[]{}<>", ")(][}{><")


# ---------- text normalisation and metrics ----------

def normalise(text):
    text = unicodedata.normalize("NFKC", text).replace("ـ", "")
    return re.sub(r"\s+", " ", text).strip()


def strip_diacritics(text):
    return DIACRITICS.sub("", text)


def cer(truth, predicted):
    return Levenshtein.distance(truth, predicted) / max(1, len(truth))


def wer(truth, predicted):
    t, p = truth.split(), predicted.split()
    return Levenshtein.distance(t, p) / max(1, len(t))


def visual_to_logical(line):
    """Converts an RTL line from visual to logical order: reverse, then restore LTR runs (Latin, digits)."""
    if not ARABIC.search(line):
        return line
    flipped = line[::-1].translate(MIRROR)
    return LTR_RUN.sub(lambda m: m.group(0)[::-1].translate(MIRROR), flipped)


def line_metrics(truth_lines, predicted_lines):
    """Order-free recognition CER (greedy best match per truth line) and a reading-order score."""
    used, matched, errors, chars = set(), [], 0, 0
    for truth in truth_lines:
        best, best_index = None, None
        for index, predicted in enumerate(predicted_lines):
            if index in used:
                continue
            d = Levenshtein.normalized_distance(truth, predicted)
            if best is None or d < best:
                best, best_index = d, index
        chars += len(truth)
        if best_index is None:
            errors += len(truth)
            continue
        used.add(best_index)
        errors += Levenshtein.distance(truth, predicted_lines[best_index])
        matched.append(best_index)
    pairs = list(zip(matched, matched[1:]))
    order = sum(1 for a, b in pairs if a < b) / len(pairs) if pairs else 1.0
    return errors / max(1, chars), order


# ---------- layout: rows and reading order ----------

def to_items(texts, scores, polys):
    items = []
    for text, score, poly in zip(texts, scores, polys):
        pts = np.asarray(poly, dtype=float).reshape(-1, 2)
        x0, y0 = pts.min(axis=0)
        x1, y1 = pts.max(axis=0)
        items.append({"text": text, "score": float(score), "x": (x0 + x1) / 2, "y": (y0 + y1) / 2,
                      "h": max(1.0, y1 - y0)})
    return items


def order_rows(items, transform):
    if not items:
        return []
    letters = "".join(i["text"] for i in items)
    rtl = len(ARABIC.findall(letters)) >= len(LATIN.findall(letters))
    median_h = statistics.median(i["h"] for i in items)
    rows = []
    for item in sorted(items, key=lambda i: i["y"]):
        if rows and abs(item["y"] - rows[-1]["y"]) <= 0.5 * median_h:
            row = rows[-1]
            row["items"].append(item)
            row["y"] = statistics.mean(i["y"] for i in row["items"])
        else:
            rows.append({"y": item["y"], "items": [item]})
    lines = []
    for row in rows:
        row_items = sorted(row["items"], key=lambda i: -i["x"] if rtl else i["x"])
        lines.append(" ".join(transform(i["text"]) for i in row_items))
    return lines


# ---------- main ----------

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--corpus", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--name", required=True)
    parser.add_argument("--det", default="PP-OCRv5_mobile_det")
    parser.add_argument("--rec", default="arabic_PP-OCRv5_mobile_rec")
    parser.add_argument("--dpi", type=int, default=300)
    parser.add_argument("--orientation", choices=["on", "off"], default="on")
    parser.add_argument("--threads", type=int, default=4)
    parser.add_argument("--mkldnn", choices=["on", "off"], default="on", help="oneDNN CPU acceleration (Paddle engine)")
    parser.add_argument("--engine", default=None, help="None = PaddlePaddle; 'onnxruntime' for ONNX Runtime")
    parser.add_argument("--det-max-side", type=int, default=None,
                        help="Downscale the detection input so its longest side is at most this (default: library)")
    parser.add_argument("--scripts", default="ar,mixed,en")
    parser.add_argument("--profiles", default="vector,clean300,noisy200,lowres150,rotated300,flip180")
    args = parser.parse_args()

    from paddleocr import PaddleOCR  # imported here so model load time is measured explicitly

    det_limits = {} if args.det_max_side is None else {"text_det_limit_type": "max",
                                                       "text_det_limit_side_len": args.det_max_side}
    started = time.perf_counter()
    ocr = PaddleOCR(text_detection_model_name=args.det, text_recognition_model_name=args.rec,
                    use_doc_orientation_classify=args.orientation == "on", use_doc_unwarping=False,
                    use_textline_orientation=args.orientation == "on", cpu_threads=args.threads, engine=args.engine,
                    enable_mkldnn=args.mkldnn == "on", **det_limits)
    load_seconds = time.perf_counter() - started

    corpus = Path(args.corpus)
    manifest = json.loads((corpus / "manifest.json").read_text(encoding="utf-8"))
    scripts, profiles = set(args.scripts.split(",")), set(args.profiles.split(","))
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    records = []

    for entry in manifest:
        if entry["script"] not in scripts or entry["profile"] not in profiles:
            continue
        t0 = time.perf_counter()
        pdf = pdfium.PdfDocument(str(corpus / entry["file"]))
        image = pdf[0].render(scale=args.dpi / 72, grayscale=False).to_numpy()
        pdf.close()
        image = np.ascontiguousarray(image[:, :, :3][:, :, ::-1]) if image.ndim == 3 else image  # RGB(A) -> BGR
        t1, c1 = time.perf_counter(), time.process_time()
        result = ocr.predict(image)[0]
        t2, c2 = time.perf_counter(), time.process_time()

        res = result.json["res"] if hasattr(result, "json") else result
        texts, scores = res["rec_texts"], res["rec_scores"]
        polys = res.get("rec_polys") or res.get("dt_polys")
        items = to_items(texts, scores, polys)
        truth_lines = [normalise(l) for l in entry["lines"]]
        truth = "\n".join(truth_lines)

        record = {"config": args.name, "file": entry["file"], "script": entry["script"], "profile": entry["profile"],
                  "font": entry["font"], "sizePt": entry["sizePt"], "renderMs": round((t1 - t0) * 1000),
                  "ocrMs": round((t2 - t1) * 1000), "ocrCpuMs": round((c2 - c1) * 1000), "boxes": len(items),
                  "lowConfidenceBoxes": sum(1 for i in items if i["score"] < 0.8),
                  "meanScore": round(statistics.mean(i["score"] for i in items), 4) if items else 0.0}
        for variant, transform in (("raw", lambda s: s), ("v2l", visual_to_logical)):
            predicted_lines = [normalise(l) for l in order_rows(items, transform)]
            predicted = "\n".join(predicted_lines)
            line_cer, order = line_metrics(truth_lines, predicted_lines)
            record[variant] = {
                "cer": round(cer(truth, predicted), 4),
                "cerNoDiacritics": round(cer(strip_diacritics(truth), strip_diacritics(predicted)), 4),
                "wer": round(wer(truth, predicted), 4),
                "lineCer": round(line_cer, 4),
                "readingOrder": round(order, 4),
            }
        record["rows"] = order_rows(items, lambda s: s)
        record["sample"] = record["rows"][:2]
        records.append(record)
        print(f"{entry['file']:<45} ocr {record['ocrMs']:>6} ms  raw cer {record['raw']['cer']:.3f}  "
              f"v2l cer {record['v2l']['cer']:.3f}", flush=True)

    peak_mb = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024
    summary = {"config": args.name, "det": args.det, "rec": args.rec, "dpi": args.dpi,
               "orientation": args.orientation, "threads": args.threads, "engine": args.engine or "paddle", "mkldnn": args.mkldnn,
               "detMaxSide": args.det_max_side, "modelLoadSeconds": round(load_seconds, 1),
               "peakRssMb": round(peak_mb), "pages": len(records)}
    with open(out / f"{args.name}.jsonl", "w", encoding="utf-8") as f:
        f.write(json.dumps({"summary": summary}, ensure_ascii=False) + "\n")
        for r in records:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(json.dumps(summary))


if __name__ == "__main__":
    main()
