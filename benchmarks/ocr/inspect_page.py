"""Dumps raw detection boxes and recognition results for one page. Usage: python inspect_page.py <pdf> [rec_model]"""
import sys
import numpy as np
import pypdfium2 as pdfium
from paddleocr import PaddleOCR

pdf = pdfium.PdfDocument(sys.argv[1])
img = np.ascontiguousarray(pdf[0].render(scale=300 / 72).to_numpy()[:, :, :3][:, :, ::-1])
rec = sys.argv[2] if len(sys.argv) > 2 else "arabic_PP-OCRv5_mobile_rec"
ocr = PaddleOCR(text_detection_model_name="PP-OCRv5_mobile_det", text_recognition_model_name=rec, use_doc_orientation_classify=False, use_doc_unwarping=False,
                use_textline_orientation=False, text_det_limit_type="max", text_det_limit_side_len=1600,
                text_rec_score_thresh=0.0)
res = ocr.predict(img)[0].json["res"]
print("det boxes:", len(res["dt_polys"]), "rec kept:", len(res["rec_texts"]))
for text, score, box in zip(res["rec_texts"], res["rec_scores"], res["rec_boxes"]):
    print(f"{score:.2f} x={box[0]:>4}-{box[2]:<4} y={box[1]:>4}  {text!r}")
