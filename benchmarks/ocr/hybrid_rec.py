"""Measures a hybrid line-level strategy: PaddleOCR detection + Arabic recognition for every line, Tesseract
(psm 7, ara+eng) re-reading Arabic line crops, and two ways of using the result.

Acceptance: a token is "recovered" when Tesseract has it, the PaddleOCR line lacks it, (with --gate en) the English
PaddleOCR recogniser also sees it, and the Arabic-only parts of both lines agree (similarity >= --agree).
Direction marks that Tesseract inserts (LRM/RLM/ALM) are removed first: they are engine artefacts, not content.

Variants reported per page:
  paddle   PaddleOCR Arabic output only
  hybrid   line REPLACEMENT: the whole Tesseract line replaces the PaddleOCR line (benchmark variant)
  merged   token INSERTION (production design): PaddleOCR text is kept unchanged; each recovered token is inserted next
           to an Arabic anchor word that matches exactly one word of the PaddleOCR line. Tokens that cannot be placed
           are not inserted and are counted as review flags. Tokens already present are never inserted twice.
Usage: python hybrid_rec.py --corpus /data/corpus --out /data/runs_hybrid [--agree 0.7]
"""
import argparse
import json
import os
import re
import resource
import subprocess
import tempfile
import time
from pathlib import Path

import numpy as np
import pypdfium2 as pdfium
from PIL import Image
from paddleocr import PaddleOCR, TextRecognition
from rapidfuzz.distance import Levenshtein

from run_benchmark import cer, line_metrics, normalise, order_rows, strip_diacritics, to_items, wer

TOKEN = re.compile(r"[A-Za-z0-9٠-٩](?:[A-Za-z0-9٠-٩@._\-/:]*[A-Za-z0-9٠-٩])?")
ARABIC = re.compile(r"[؀-ۿ]")
NON_ARABIC = re.compile(r"[^؀-ۿ ]")
DIRECTION_MARKS = re.compile(r"[‎‏؜]")
EASTERN = str.maketrans("٠١٢٣٤٥٦٧٨٩", "0123456789")


def tokens(text):
    return {t.translate(EASTERN) for t in TOKEN.findall(text)}


def arabic_only(text):
    return re.sub(r"\s+", " ", NON_ARABIC.sub(" ", text)).strip()


WORD_STRIP = re.compile(r"[^\u0600-\u06FF]")
DIACRITIC_MARKS = re.compile(r"[\u064B-\u065F\u0670\u0640]")


def anchor_key(word):
    return WORD_STRIP.sub("", DIACRITIC_MARKS.sub("", word))


def unique_match(words, anchor):
    """Index of the only word of `words` matching `anchor` (exact, else edit distance <= 1 for words >= 4 letters)."""
    key = anchor_key(anchor)
    if len(key) < 2:
        return None
    exact = [i for i, w in enumerate(words) if anchor_key(w) == key]
    if len(exact) == 1:
        return exact[0]
    if exact or len(key) < 4:
        return None
    near = [i for i, w in enumerate(words)
            if len(anchor_key(w)) >= 3 and Levenshtein.distance(anchor_key(w), key) <= 1]
    return near[0] if len(near) == 1 else None


def insert_tokens(paddle_text, tess_text, recovered):
    """Inserts recovered tokens into the PaddleOCR line without changing any of its characters.

    Returns (merged_text, inserted, unplaced, duplicates)."""
    words = paddle_text.split()
    tess_words = tess_text.split()
    inserted, unplaced, duplicates = [], [], []
    for token in sorted(recovered):
        if token in tokens(" ".join(words)):
            duplicates.append(token)
            continue
        position = next((i for i, w in enumerate(tess_words) if token in tokens(w)), None)
        if position is None:
            unplaced.append(token)
            continue
        source_word = tess_words[position]
        before = next((w for w in reversed(tess_words[:position]) if ARABIC.search(w)), None)
        after = next((w for w in tess_words[position + 1:] if ARABIC.search(w)), None)
        target = None
        if before is not None:
            j = unique_match(words, before)
            if j is not None:
                target = j + 1
        if target is None and after is not None:
            j = unique_match(words, after)
            if j is not None:
                target = j
        if target is None:
            unplaced.append(token)
            continue
        # insert the token itself, not Tesseract's whole word when that word also carries (possibly misread) Arabic
        piece = token if ARABIC.search(source_word) else source_word
        words.insert(target, piece)
        inserted.append(token)
    merged = " ".join(words)
    # safety checks: Arabic content unchanged and every inserted token present exactly as recovered
    if arabic_only(merged) != arabic_only(paddle_text) or not set(inserted) <= tokens(merged):
        return paddle_text, [], sorted(set(inserted) | set(unplaced)), duplicates
    return merged, inserted, unplaced, duplicates


def tesseract_line(crop, workdir, threads):
    path = Path(workdir) / "line.png"
    Image.fromarray(crop[:, :, ::-1]).convert("L").save(path, dpi=(300, 300))
    done = subprocess.run(["tesseract", str(path), "stdout", "-l", "ara+eng", "--psm", "7", "--dpi", "300"],
                          capture_output=True, text=True, timeout=60,
                          env=dict(os.environ, OMP_THREAD_LIMIT=str(threads)))
    return DIRECTION_MARKS.sub("", done.stdout).strip()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--corpus", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--agree", type=float, default=0.7)
    parser.add_argument("--gate", choices=["none", "en"], default="none",
                        help="en: re-read with Tesseract only lines where the English PaddleOCR recognizer finds "
                             "Latin/digit tokens missing from the Arabic line, and accept only tokens both engines see")
    parser.add_argument("--profiles", default="vector,clean300,noisy200,lowres150,rotated300")
    args = parser.parse_args()

    ocr = PaddleOCR(text_detection_model_name="PP-OCRv5_mobile_det", text_recognition_model_name="arabic_PP-OCRv5_mobile_rec",
                    use_doc_orientation_classify=False, use_doc_unwarping=False, use_textline_orientation=False,
                    text_det_limit_type="max", text_det_limit_side_len=1600, cpu_threads=4)
    latin = TextRecognition(model_name="en_PP-OCRv5_mobile_rec", cpu_threads=4) if args.gate == "en" else None
    corpus = Path(args.corpus)
    manifest = json.loads((corpus / "manifest.json").read_text(encoding="utf-8"))
    records = []

    with tempfile.TemporaryDirectory() as tmp:
        for entry in manifest:
            if entry["script"] not in ("ar", "mixed") or entry["profile"] not in set(args.profiles.split(",")):
                continue
            pdf = pdfium.PdfDocument(str(corpus / entry["file"]))
            image = np.ascontiguousarray(pdf[0].render(scale=300 / 72).to_numpy()[:, :, :3][:, :, ::-1])
            pdf.close()

            t0 = time.perf_counter()
            res = ocr.predict(image)[0].json["res"]
            t1 = time.perf_counter()
            chosen, merged_texts, replaced, tess_calls = [], [], 0, 0
            inserted_all, unplaced_all, duplicates_all, unresolved_hints = [], [], [], []
            crops = []
            for box in res["rec_boxes"]:
                x0, y0, x1, y1 = (int(v) for v in box)
                pad = 8
                crops.append(np.ascontiguousarray(image[max(0, y0 - pad):y1 + pad, max(0, x0 - pad):x1 + pad]))
            arabic_idx = [i for i, t in enumerate(res["rec_texts"]) if ARABIC.search(t)]
            hints = {}
            if latin is not None and arabic_idx:
                outs = latin.predict([crops[i] for i in arabic_idx], batch_size=8)
                hints = {i: tokens(o.json["res"]["rec_text"]) for i, o in zip(arabic_idx, outs)}
            for i, text in enumerate(res["rec_texts"]):
                if i not in arabic_idx:
                    chosen.append(text)
                    merged_texts.append(text)
                    continue
                hinted = set()
                if latin is not None:
                    hinted = {t for t in hints.get(i, set()) - tokens(text) if len(t) >= 2}
                    if not hinted:
                        chosen.append(text)
                        merged_texts.append(text)
                        continue
                tess = tesseract_line(crops[i], tmp, 1)
                tess_calls += 1
                gained = tokens(tess) - tokens(text)
                if latin is not None:
                    gained &= hints.get(i, set())
                agree = 1 - Levenshtein.normalized_distance(arabic_only(text), arabic_only(tess))
                if gained and agree >= args.agree:
                    chosen.append(tess)
                    replaced += 1
                    merged, inserted, unplaced, duplicates = insert_tokens(text, tess, gained)
                    merged_texts.append(merged)
                    inserted_all += inserted
                    unplaced_all += unplaced
                    duplicates_all += duplicates
                else:
                    chosen.append(text)
                    merged_texts.append(text)
                # tokens the English pass saw that are still absent: "possibly missing" review flag
                unresolved_hints += sorted(hinted - tokens(merged_texts[-1]))
            t2 = time.perf_counter()

            truth_lines = [normalise(l) for l in entry["lines"]]
            truth = "\n".join(truth_lines)
            truth_tokens = set().union(*(tokens(l) for l in truth_lines))
            result = {"file": entry["file"], "profile": entry["profile"], "replacedLines": replaced, "tesseractCalls": tess_calls,
                      "paddleMs": round((t1 - t0) * 1000), "tesseractMs": round((t2 - t1) * 1000),
                      "insertedTokens": inserted_all, "unplacedTokens": unplaced_all,
                      "duplicatesSkipped": duplicates_all,
                      "reviewFlags": sorted(set(unplaced_all) | set(unresolved_hints))}
            result["falseInsertions"] = sorted(set(inserted_all) - truth_tokens)
            result["falseFlags"] = sorted(set(result["reviewFlags"]) - truth_tokens)
            result["arabicPreserved"] = all(arabic_only(m) == arabic_only(p)
                                            for m, p in zip(merged_texts, res["rec_texts"]))
            for variant, texts in (("paddle", res["rec_texts"]), ("hybrid", chosen), ("merged", merged_texts)):
                rows = [normalise(r) for r in order_rows(to_items(texts, res["rec_scores"], res["rec_polys"]), str)]
                predicted = "\n".join(rows)
                found = set().union(set(), *(tokens(r) for r in rows))
                result[variant] = {
                    "cer": round(cer(truth, predicted), 4),
                    "cerNoDiacritics": round(cer(strip_diacritics(truth), strip_diacritics(predicted)), 4),
                    "wer": round(wer(truth, predicted), 4),
                    "tokenRecall": round(len(truth_tokens & found) / len(truth_tokens), 3) if truth_tokens else None,
                    "rows": rows}
            records.append(result)
            print(f"{entry['file']:<45} cer paddle {result['paddle']['cer']:.3f} -> replace {result['hybrid']['cer']:.3f}"
                  f" / insert {result['merged']['cer']:.3f}  tokens {result['paddle']['tokenRecall']} -> "
                  f"{result['hybrid']['tokenRecall']} / {result['merged']['tokenRecall']}"
                  f"  ins {len(inserted_all)} unplaced {len(unplaced_all)} falseIns {len(result['falseInsertions'])}"
                  f"  replaced {replaced}/{tess_calls}  +{result['tesseractMs']} ms", flush=True)

    def mean(key, variant):
        values = [r[variant][key] for r in records if r[variant][key] is not None]
        return round(sum(values) / len(values), 4)

    summary = {"pages": len(records), "agree": args.agree, "gate": args.gate,
               "cerPaddle": mean("cer", "paddle"), "cerHybrid": mean("cer", "hybrid"),
               "tokenRecallPaddle": mean("tokenRecall", "paddle"), "tokenRecallHybrid": mean("tokenRecall", "hybrid"),
               "cerMerged": mean("cer", "merged"), "tokenRecallMerged": mean("tokenRecall", "merged"),
               "insertedTokens": sum(len(r["insertedTokens"]) for r in records),
               "falseInsertions": sum(len(r["falseInsertions"]) for r in records),
               "unplacedTokens": sum(len(r["unplacedTokens"]) for r in records),
               "duplicatesSkipped": sum(len(r["duplicatesSkipped"]) for r in records),
               "reviewFlags": sum(len(r["reviewFlags"]) for r in records),
               "falseFlags": sum(len(r["falseFlags"]) for r in records),
               "arabicPreservedAllPages": all(r["arabicPreserved"] for r in records),
               "tesseractOverhead": round(sum(r["tesseractMs"] for r in records) / sum(r["paddleMs"] for r in records), 3),
               "peakRssMb": round(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024),
               "peakRssTesseractMb": round(resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss / 1024)}
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    with open(out / f"hybrid_agree{int(args.agree * 100)}_gate{args.gate}.jsonl", "w", encoding="utf-8") as f:
        f.write(json.dumps({"summary": summary}, ensure_ascii=False) + "\n")
        for r in records:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(json.dumps(summary))


if __name__ == "__main__":
    main()
