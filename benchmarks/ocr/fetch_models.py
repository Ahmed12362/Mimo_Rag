"""Downloads every candidate model once at image build time (runs are then offline)."""
from paddleocr import PaddleOCR

# (detection, recognition, inference engine); None = PaddlePaddle default
CANDIDATES = [
    ("PP-OCRv5_mobile_det", "arabic_PP-OCRv5_mobile_rec", None),
    ("PP-OCRv5_mobile_det", "en_PP-OCRv5_mobile_rec", None),
    ("PP-OCRv5_server_det", "arabic_PP-OCRv5_mobile_rec", None),
    ("PP-OCRv5_mobile_det", "arabic_PP-OCRv5_mobile_rec", "onnxruntime"),
    ("PP-OCRv5_mobile_det", "en_PP-OCRv5_mobile_rec", "onnxruntime"),
    ("PP-OCRv5_server_det", "arabic_PP-OCRv5_mobile_rec", "onnxruntime"),
]

for det, rec, engine in CANDIDATES:
    try:
        PaddleOCR(text_detection_model_name=det, text_recognition_model_name=rec, use_doc_orientation_classify=True,
                  use_doc_unwarping=False, use_textline_orientation=True, engine=engine)
        print(f"fetched {det} + {rec} ({engine or 'paddle'})")
    except Exception as error:  # an unavailable candidate is reported, not fatal for the other candidates
        print(f"UNAVAILABLE {det} + {rec} ({engine}): {type(error).__name__}: {error}")
