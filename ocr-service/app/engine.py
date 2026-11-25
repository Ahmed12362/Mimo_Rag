"""The OCR pipeline (one instance per worker process): PaddleOCR Arabic + English gate + Tesseract, conditional
orientation. Configuration follows the approved slice 2a candidate (docs/benchmarks/ocr-benchmark-2a.md §7.1)."""
import os
import statistics
import subprocess
import time
from dataclasses import asdict

import cv2
import numpy as np

from . import PIPELINE_VERSION
from .merge import has_arabic, hinted_tokens, merge_line
from .orientation import better, candidate_rotations, needs_orientation_check, rotate, unrotate_box

DETECTION_MODEL = "PP-OCRv5_mobile_det"
RECOGNITION_MODEL = "arabic_PP-OCRv5_mobile_rec"
GATE_MODEL = "en_PP-OCRv5_mobile_rec"
ORIENTATION_MODEL = "PP-LCNet_x1_0_doc_ori"
DETECTION_MAX_SIDE = 1600
CROP_PADDING = 8
TESSERACT_TIMEOUT = 15


class InvalidImage(Exception):
    pass


class OcrEngine:

    def __init__(self, threads: int, low_confidence: float):
        from paddleocr import DocImgOrientationClassification, PaddleOCR, TextRecognition

        self.low_confidence = low_confidence
        self.ocr = PaddleOCR(text_detection_model_name=DETECTION_MODEL, text_recognition_model_name=RECOGNITION_MODEL,
                             use_doc_orientation_classify=False, use_doc_unwarping=False,
                             use_textline_orientation=False, text_det_limit_type="max",
                             text_det_limit_side_len=DETECTION_MAX_SIDE, cpu_threads=threads, enable_mkldnn=True)
        self.latin = TextRecognition(model_name=GATE_MODEL, cpu_threads=threads)
        self.orientation = DocImgOrientationClassification(model_name=ORIENTATION_MODEL, cpu_threads=threads)
        self.versions = engine_versions()

    # ---- public ---------------------------------------------------------------------------------------------------

    def recognize(self, png: bytes, orientation_mode: str) -> dict:
        started = time.perf_counter()
        image = cv2.imdecode(np.frombuffer(png, dtype=np.uint8), cv2.IMREAD_COLOR)
        if image is None:
            raise InvalidImage("The PNG could not be decoded.")
        height, width = image.shape[:2]
        timings = {"orientationMs": 0, "ocrMs": 0, "gateMs": 0, "tesseractMs": 0}

        page = self._page(image, timings)
        orientation = {"checked": False, "applied": 0, "classifierConfidence": None}
        warnings: list[str] = []
        # Orientation (slice 2d, decisions B1 + follow-up): the classifier runs on every page; in addition, a page that
        # reads poorly is also tried upside down whatever the classifier says (it misses about half of the upside-down
        # pages rendered by PDFBox, with scores near 0.56). A rotation is applied only if its reading is clearly
        # better (orientation.better), so upright pages stay unchanged.
        if orientation_mode == "auto":
            orientation["checked"] = True
            t = time.perf_counter()
            result = self.orientation.predict(image)[0].json["res"]
            timings["orientationMs"] += int((time.perf_counter() - t) * 1000)
            label = int(result["label_names"][0])
            orientation["classifierConfidence"] = round(float(result["scores"][0]), 4)
            for candidate in candidate_rotations(label, page["summary"]):
                quarter_turns = (4 - candidate // 90) % 4  # the page is rotated CCW by `candidate`; turn it back
                corrected = self._page(rotate(image, quarter_turns), timings)
                if better(corrected["summary"], page["summary"]):
                    for line in corrected["lines"]:
                        line["box"] = unrotate_box(line["box"], quarter_turns, width, height)
                    page = corrected
                    orientation["applied"] = candidate
                    warnings.append("ORIENTATION_CORRECTED")
                    break
            if orientation["applied"] == 0 and needs_orientation_check(page["summary"]):
                warnings.append("ORIENTATION_UNCERTAIN")
        if page["summary"]["characters"] == 0:
            warnings.append("NO_TEXT_FOUND")
        for flag in ("TOKEN_RECOVERED", "TOKEN_MISSING_UNPLACED", "TOKEN_POSSIBLY_MISSING", "LOW_CONFIDENCE"):
            if any(flag in line["flags"] for line in page["lines"]):
                warnings.append(flag)
        timings["totalMs"] = int((time.perf_counter() - started) * 1000)
        return {"engine": self.versions, "image": {"width": width, "height": height}, "orientation": orientation,
                "lines": page["lines"], "summary": page["summary"], "warnings": warnings, "timings": timings}

    # ---- pipeline -------------------------------------------------------------------------------------------------

    def _page(self, image: np.ndarray, timings: dict) -> dict:
        t = time.perf_counter()
        res = self.ocr.predict(image)[0].json["res"]
        timings["ocrMs"] += int((time.perf_counter() - t) * 1000)
        texts, scores, boxes = res.get("rec_texts", []), res.get("rec_scores", []), res.get("rec_boxes", [])
        crops = [_crop(image, box) for box in boxes]
        arabic = [i for i, text in enumerate(texts) if has_arabic(text)]

        latin_reads: dict[int, str] = {}
        if arabic:
            t = time.perf_counter()
            outputs = self.latin.predict([crops[i] for i in arabic], batch_size=8)
            latin_reads = {i: out.json["res"]["rec_text"] for i, out in zip(arabic, outputs)}
            timings["gateMs"] += int((time.perf_counter() - t) * 1000)

        lines = []
        for i, (text, score, box) in enumerate(zip(texts, scores, boxes)):
            x0, y0, x1, y1 = (int(v) for v in box)
            flags: list[str] = []
            proposed, recovered = text, []
            if i in latin_reads and hinted_tokens(text, latin_reads[i]):
                t = time.perf_counter()
                tess = _tesseract(crops[i])
                timings["tesseractMs"] += int((time.perf_counter() - t) * 1000)
                merged = merge_line(text, latin_reads[i], tess)
            elif i in latin_reads:
                merged = merge_line(text, latin_reads[i], None)
            else:
                merged = None
            if merged is not None:
                proposed, recovered = merged.proposed_text, [asdict(r) for r in merged.recovered]
                if merged.recovered:
                    flags.append("TOKEN_RECOVERED")
                if merged.unplaced:
                    flags.append("TOKEN_MISSING_UNPLACED")
                if merged.possibly_missing:
                    flags.append("TOKEN_POSSIBLY_MISSING")
            if float(score) < self.low_confidence:
                flags.append("LOW_CONFIDENCE")
            lines.append({"box": {"x": x0, "y": y0, "width": max(0, x1 - x0), "height": max(0, y1 - y0)},
                          "direction": "RTL" if has_arabic(text) else "LTR", "confidence": round(float(score), 4),
                          "originalText": text, "proposedText": proposed, "recoveredTokens": recovered,
                          "unplacedTokens": merged.unplaced if merged else [],
                          "possiblyMissingTokens": merged.possibly_missing if merged else [], "flags": flags})
        lines = _reading_order(lines)
        summary = _summary(lines, self.low_confidence)
        return {"lines": lines, "summary": summary}


def _crop(image: np.ndarray, box) -> np.ndarray:
    x0, y0, x1, y1 = (int(v) for v in box)
    h, w = image.shape[:2]
    return np.ascontiguousarray(image[max(0, y0 - CROP_PADDING):min(h, y1 + CROP_PADDING),
                                      max(0, x0 - CROP_PADDING):min(w, x1 + CROP_PADDING)])


def _tesseract(crop: np.ndarray) -> str | None:
    """One line with Tesseract (psm 7), through stdin/stdout only: nothing is written to disk."""
    ok, encoded = cv2.imencode(".png", cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY))
    if not ok:
        return None
    try:
        done = subprocess.run(["tesseract", "stdin", "stdout", "-l", "ara+eng", "--psm", "7", "--dpi", "300"],
                              input=encoded.tobytes(), capture_output=True, timeout=TESSERACT_TIMEOUT,
                              env=dict(os.environ, OMP_THREAD_LIMIT="1"))
    except subprocess.TimeoutExpired:
        return None
    return done.stdout.decode("utf-8", errors="replace").strip() if done.returncode == 0 else None


def _reading_order(lines: list[dict]) -> list[dict]:
    """Rows by vertical overlap, right-to-left within a row when Arabic dominates (same rule as the 2a benchmark)."""
    if not lines:
        return lines
    letters = "".join(line["originalText"] for line in lines)
    arabic = sum(1 for c in letters if "؀" <= c <= "ۿ")
    latin = sum(1 for c in letters if c.isascii() and c.isalpha())
    rtl = arabic >= latin
    median_h = statistics.median(max(1, line["box"]["height"]) for line in lines)
    rows: list[dict] = []
    for line in sorted(lines, key=lambda l: l["box"]["y"] + l["box"]["height"] / 2):
        centre = line["box"]["y"] + line["box"]["height"] / 2
        if rows and abs(centre - rows[-1]["centre"]) <= 0.5 * median_h:
            rows[-1]["lines"].append(line)
            rows[-1]["centre"] = statistics.mean(l["box"]["y"] + l["box"]["height"] / 2 for l in rows[-1]["lines"])
        else:
            rows.append({"centre": centre, "lines": [line]})
    ordered = []
    for row_index, row in enumerate(rows):
        for line in sorted(row["lines"], key=lambda l: -(l["box"]["x"]) if rtl else l["box"]["x"]):
            line["row"] = row_index
            ordered.append(line)
    return ordered


def _summary(lines: list[dict], low_confidence: float) -> dict:
    confidences = [line["confidence"] for line in lines]
    return {"lines": len(lines),
            "characters": sum(len(line["originalText"].replace(" ", "")) for line in lines),
            "meanConfidence": round(statistics.mean(confidences), 4) if confidences else 0.0,
            "lowConfidenceLines": sum(1 for c in confidences if c < low_confidence),
            "recoveredTokens": sum(len(line["recoveredTokens"]) for line in lines),
            "unplacedTokens": sum(len(line["unplacedTokens"]) for line in lines),
            "possiblyMissingTokens": sum(len(line["possiblyMissingTokens"]) for line in lines)}


def engine_versions() -> dict:
    from importlib.metadata import version

    tesseract = subprocess.run(["tesseract", "--version"], capture_output=True, text=True).stdout.split()
    return {"pipeline": PIPELINE_VERSION, "paddleocr": version("paddleocr"), "paddlepaddle": version("paddlepaddle"),
            "tesseract": tesseract[1] if len(tesseract) > 1 else "unknown", "detection": DETECTION_MODEL,
            "recognition": RECOGNITION_MODEL, "gate": GATE_MODEL, "orientation": ORIENTATION_MODEL,
            "detectionMaxSide": DETECTION_MAX_SIDE}
