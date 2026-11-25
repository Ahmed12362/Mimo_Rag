"""Downloads the selected models once, at image build time."""
from paddleocr import DocImgOrientationClassification, PaddleOCR, TextRecognition

PaddleOCR(text_detection_model_name="PP-OCRv5_mobile_det", text_recognition_model_name="arabic_PP-OCRv5_mobile_rec",
          use_doc_orientation_classify=False, use_doc_unwarping=False, use_textline_orientation=False)
TextRecognition(model_name="en_PP-OCRv5_mobile_rec")
DocImgOrientationClassification(model_name="PP-LCNet_x1_0_doc_ori")
print("models ready")
