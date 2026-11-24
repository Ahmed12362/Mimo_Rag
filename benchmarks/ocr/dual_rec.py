"""Measures the dual-recognition mitigation for Latin/digit runs inside Arabic lines.

Pipeline under test: detection + Arabic recognition (as in pd_300_d1600), then the English recognizer on the same line
crops. A Latin/digit token found by the English recognizer but absent from the Arabic text is reported as
"missing LTR token" (in production: a page warning for the reviewer, with the recovered token).

Metrics per page, over ground-truth Latin/digit tokens (e.g. HRIS, 1500, 2026/01/01, hr-support@example.com):
  arabicRecall    share of truth tokens present in the Arabic output
  combinedRecall  share present in the Arabic output OR in the flagged tokens
  falseFlags      flagged tokens that are not truth tokens (noise the reviewer would see)
Usage: python dual_rec.py --corpus /data/corpus --out /data/runs_dual
"""
import argparse
import json
import re
import resource
import time
from pathlib import Path

import numpy as np
import pypdfium2 as pdfium
from paddleocr import PaddleOCR, TextRecognition

from run_benchmark import normalise

TOKEN = re.compile(r"[A-Za-z0-9٠-٩](?:[A-Za-z0-9٠-٩@._\-/:]*[A-Za-z0-9٠-٩])?")
ARABIC = re.compile(r"[؀-ۿ]")
EASTERN = str.maketrans("٠١٢٣٤٥٦٧٨٩", "0123456789")


def tokens(text):
    return {t.translate(EASTERN) for t in TOKEN.findall(text)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--corpus", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--profiles", default="vector,clean300,noisy200,lowres150,rotated300")
    args = parser.parse_args()

    ocr = PaddleOCR(text_detection_model_name="PP-OCRv5_mobile_det", text_recognition_model_name="arabic_PP-OCRv5_mobile_rec",
                    use_doc_orientation_classify=False, use_doc_unwarping=False, use_textline_orientation=False,
                    text_det_limit_type="max", text_det_limit_side_len=1600, cpu_threads=4)
    latin = TextRecognition(model_name="en_PP-OCRv5_mobile_rec", cpu_threads=4)
    corpus = Path(args.corpus)
    manifest = json.loads((corpus / "manifest.json").read_text(encoding="utf-8"))
    records = []

    for entry in manifest:
        if entry["script"] not in ("ar", "mixed") or entry["profile"] not in set(args.profiles.split(",")):
            continue
        truth_tokens = set().union(*(tokens(normalise(l)) for l in entry["lines"]))
        if not truth_tokens:
            continue
        pdf = pdfium.PdfDocument(str(corpus / entry["file"]))
        image = np.ascontiguousarray(pdf[0].render(scale=300 / 72).to_numpy()[:, :, :3][:, :, ::-1])
        pdf.close()

        t0 = time.perf_counter()
        res = ocr.predict(image)[0].json["res"]
        t1 = time.perf_counter()
        crops, arabic_lines = [], []
        for text, box in zip(res["rec_texts"], res["rec_boxes"]):
            if ARABIC.search(text):  # only Arabic lines need the second pass
                x0, y0, x1, y1 = (int(v) for v in box)
                crops.append(np.ascontiguousarray(image[max(0, y0):y1, max(0, x0):x1]))
                arabic_lines.append(text)
        latin_texts = [r.json["res"]["rec_text"] for r in latin.predict(crops, batch_size=8)] if crops else []
        t2 = time.perf_counter()

        arabic_tokens = set().union(set(), *(tokens(t) for t in res["rec_texts"]))
        flagged = set()
        for arabic_text, latin_text in zip(arabic_lines, latin_texts):
            flagged |= {t for t in tokens(latin_text) if t not in tokens(arabic_text) and len(t) >= 2}
        records.append({
            "file": entry["file"], "profile": entry["profile"], "truthTokens": len(truth_tokens),
            "arabicRecall": round(len(truth_tokens & arabic_tokens) / len(truth_tokens), 3),
            "combinedRecall": round(len(truth_tokens & (arabic_tokens | flagged)) / len(truth_tokens), 3),
            "falseFlags": len(flagged - truth_tokens), "missedTokens": sorted(truth_tokens - arabic_tokens - flagged),
            "pipelineMs": round((t1 - t0) * 1000), "secondPassMs": round((t2 - t1) * 1000), "lines": len(crops)})
        r = records[-1]
        print(f"{entry['file']:<45} arabic {r['arabicRecall']:.2f}  combined {r['combinedRecall']:.2f}  "
              f"false {r['falseFlags']}  +{r['secondPassMs']} ms on {r['pipelineMs']} ms  missed {r['missedTokens']}",
              flush=True)

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    summary = {"pages": len(records),
               "arabicRecall": round(sum(r["arabicRecall"] for r in records) / len(records), 3),
               "combinedRecall": round(sum(r["combinedRecall"] for r in records) / len(records), 3),
               "falseFlagsPerPage": round(sum(r["falseFlags"] for r in records) / len(records), 2),
               "secondPassOverhead": round(sum(r["secondPassMs"] for r in records) / sum(r["pipelineMs"] for r in records), 3),
               "peakRssMb": round(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024)}
    with open(out / "dual_rec.jsonl", "w", encoding="utf-8") as f:
        f.write(json.dumps({"summary": summary}, ensure_ascii=False) + "\n")
        for r in records:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(json.dumps(summary))


if __name__ == "__main__":
    main()
