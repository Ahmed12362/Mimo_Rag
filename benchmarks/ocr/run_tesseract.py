"""Runs Tesseract 5 (Debian bookworm package) over the corpus with the same metrics as run_benchmark.py.

CPU time and peak memory are taken from the tesseract child processes (RUSAGE_CHILDREN).
Usage: python run_tesseract.py --corpus /data/corpus --out /data/runs --name tess_300_psm3 --psm 3 --threads 4
"""
import argparse
import json
import os
import resource
import subprocess
import tempfile
import time
from pathlib import Path

import pypdfium2 as pdfium

from run_benchmark import cer, line_metrics, normalise, strip_diacritics, wer


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--corpus", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--name", required=True)
    parser.add_argument("--dpi", type=int, default=300)
    parser.add_argument("--psm", default="3")
    parser.add_argument("--langs", default="ara+eng")
    parser.add_argument("--threads", type=int, default=4)
    parser.add_argument("--scripts", default="ar,mixed,en")
    parser.add_argument("--profiles", default="vector,clean300,noisy200,lowres150,rotated300,flip180")
    args = parser.parse_args()

    env = dict(os.environ, OMP_THREAD_LIMIT=str(args.threads))
    version = subprocess.run(["tesseract", "--version"], capture_output=True, text=True).stdout.splitlines()[0]
    corpus = Path(args.corpus)
    manifest = json.loads((corpus / "manifest.json").read_text(encoding="utf-8"))
    scripts, profiles = set(args.scripts.split(",")), set(args.profiles.split(","))
    records = []

    with tempfile.TemporaryDirectory() as tmp:
        for entry in manifest:
            if entry["script"] not in scripts or entry["profile"] not in profiles:
                continue
            t0 = time.perf_counter()
            pdf = pdfium.PdfDocument(str(corpus / entry["file"]))
            image_path = Path(tmp) / "page.png"
            pdf[0].render(scale=args.dpi / 72).to_pil().convert("L").save(image_path, dpi=(args.dpi, args.dpi))
            pdf.close()
            t1 = time.perf_counter()
            before = resource.getrusage(resource.RUSAGE_CHILDREN)
            done = subprocess.run(["tesseract", str(image_path), "stdout", "-l", args.langs, "--psm", args.psm,
                                   "--dpi", str(args.dpi)], capture_output=True, text=True, env=env, timeout=300)
            after = resource.getrusage(resource.RUSAGE_CHILDREN)
            t2 = time.perf_counter()

            rows = [line for line in done.stdout.splitlines() if line.strip()]
            truth_lines = [normalise(l) for l in entry["lines"]]
            predicted_lines = [normalise(l) for l in rows]
            truth, predicted = "\n".join(truth_lines), "\n".join(predicted_lines)
            line_cer, order = line_metrics(truth_lines, predicted_lines)
            cpu_ms = ((after.ru_utime - before.ru_utime) + (after.ru_stime - before.ru_stime)) * 1000
            metrics = {"cer": round(cer(truth, predicted), 4),
                       "cerNoDiacritics": round(cer(strip_diacritics(truth), strip_diacritics(predicted)), 4),
                       "wer": round(wer(truth, predicted), 4), "lineCer": round(line_cer, 4),
                       "readingOrder": round(order, 4)}
            record = {"config": args.name, "file": entry["file"], "script": entry["script"],
                      "profile": entry["profile"], "font": entry["font"], "sizePt": entry["sizePt"],
                      "renderMs": round((t1 - t0) * 1000), "ocrMs": round((t2 - t1) * 1000),
                      "ocrCpuMs": round(cpu_ms), "boxes": len(rows), "lowConfidenceBoxes": 0, "meanScore": 0.0,
                      "raw": metrics, "v2l": metrics, "rows": rows, "sample": rows[:2]}
            records.append(record)
            print(f"{entry['file']:<45} ocr {record['ocrMs']:>6} ms  cer {metrics['cer']:.3f}", flush=True)

    peak_mb = resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss / 1024
    summary = {"config": args.name, "det": "tesseract", "rec": f"{version} {args.langs} psm{args.psm}",
               "dpi": args.dpi, "orientation": "off", "threads": args.threads, "engine": "tesseract",
               "modelLoadSeconds": 0.0, "peakRssMb": round(peak_mb), "pages": len(records)}
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    with open(out / f"{args.name}.jsonl", "w", encoding="utf-8") as f:
        f.write(json.dumps({"summary": summary}, ensure_ascii=False) + "\n")
        for r in records:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(json.dumps(summary))


if __name__ == "__main__":
    main()
