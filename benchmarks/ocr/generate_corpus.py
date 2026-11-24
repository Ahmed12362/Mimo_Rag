"""Generates the synthetic OCR corpus (M2 slice 2a).

Digital pages are rendered with WeasyPrint (Pango/HarfBuzz shaping, so the PDF carries real Unicode text).
"Scanned" variants are rasterised, degraded, and wrapped as image-only PDFs. Ground truth goes to manifest.json.

Usage (inside the bench image): python generate_corpus.py /out/corpus
"""
import html
import io
import json
import random
import sys
from pathlib import Path

import numpy as np
import pypdfium2 as pdfium
from PIL import Image, ImageFilter
from weasyprint import HTML

from corpus_text import ALL_PAGES, ground_truth_lines

# name: (scan dpi, rotation deg, blur radius, noise sigma, jpeg quality, salt-and-pepper ratio)
SCAN_PROFILES = {
    "clean300": (300, 0.0, 0.0, 4, 85, 0.0),
    "noisy200": (200, 1.5, 0.8, 12, 55, 0.002),
    "lowres150": (150, 0.0, 0.6, 6, 70, 0.0),
    "rotated300": (300, 3.0, 0.3, 6, 80, 0.0),
    "flip180": (300, 180.0, 0.3, 6, 80, 0.0),  # page fed upside down into the scanner
}


def page_html(direction, font, size_pt, lines):
    rows = []
    for line in lines:
        if isinstance(line, list):
            cells = "".join(f"<td>{html.escape(c)}</td>" for c in line)
            rows.append(f"<table><tr>{cells}</tr></table>")
        else:
            lang = "en" if direction == "ltr" else "ar"
            rows.append(f'<p lang="{lang}">{html.escape(line)}</p>')
    return f"""<!doctype html><html dir="{direction}"><head><meta charset="utf-8"><style>
      @page {{ size: A4; margin: 22mm 20mm; }}
      body {{ font-family: '{font}', 'Noto Sans', 'Noto Naskh Arabic'; font-size: {size_pt}pt; }}
      p {{ white-space: nowrap; margin: 0 0 {size_pt * 0.9:.1f}pt 0; unicode-bidi: plaintext; }}
      p:first-child {{ font-size: {size_pt * 1.35:.1f}pt; font-weight: bold; margin-bottom: {size_pt * 1.4:.1f}pt; }}
      table {{ border-collapse: collapse; margin: 0; width: 100%; table-layout: fixed; }}
      td {{ border: 1px solid #333; padding: 5pt 6pt; white-space: nowrap; }}
    </style></head><body>{''.join(rows)}</body></html>"""


def degrade(image, rotation, blur, sigma, quality, salt_pepper, rng):
    img = image.convert("L")
    if rotation:
        img = img.rotate(rotation, resample=Image.BICUBIC, expand=False, fillcolor=255)
    if blur:
        img = img.filter(ImageFilter.GaussianBlur(blur))
    arr = np.asarray(img, dtype=np.float32)
    arr = arr * 0.92 + 10  # slight contrast loss, like a photocopy
    arr += rng.normal(0, sigma, arr.shape)
    if salt_pepper:
        mask = rng.random(arr.shape)
        arr[mask < salt_pepper / 2] = 0
        arr[mask > 1 - salt_pepper / 2] = 255
    img = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8))
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=quality)
    return Image.open(io.BytesIO(buf.getvalue()))


def main(out_dir):
    out = Path(out_dir)
    (out / "digital").mkdir(parents=True, exist_ok=True)
    (out / "scanned").mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(20261124)
    random.seed(20261124)
    manifest = []

    for page_id, direction, font, size, lines in ALL_PAGES:
        digital = out / "digital" / f"{page_id}.pdf"
        HTML(string=page_html(direction, font, size, lines)).write_pdf(digital)
        gt = ground_truth_lines(lines)
        script = page_id.split("_")[0]
        manifest.append({"file": f"digital/{page_id}.pdf", "page": page_id, "script": script, "font": font,
                         "sizePt": size, "kind": "digital", "profile": "vector", "lines": gt})

        for profile, (dpi, rot, blur, sigma, quality, sp) in SCAN_PROFILES.items():
            pdf = pdfium.PdfDocument(str(digital))
            bitmap = pdf[0].render(scale=dpi / 72).to_pil()
            pdf.close()
            scanned = degrade(bitmap, rot, blur, sigma, quality, sp, rng)
            target = out / "scanned" / f"{page_id}__{profile}.pdf"
            scanned.save(target, format="PDF", resolution=dpi)
            manifest.append({"file": f"scanned/{page_id}__{profile}.pdf", "page": page_id, "script": script,
                             "font": font, "sizePt": size, "kind": "scanned", "profile": profile, "lines": gt})

    (out / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"{len(manifest)} files written to {out}")


if __name__ == "__main__":
    main(sys.argv[1])
