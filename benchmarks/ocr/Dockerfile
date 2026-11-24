# OCR benchmark image (M2 slice 2a). Development tool only; not the production OCR sidecar.
# Build: docker build -t mimorag-ocr-bench:2a benchmarks/ocr
FROM python:3.11.11-slim-bookworm

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK=True \
    PADDLE_PDX_CACHE_HOME=/opt/paddlex

# Pango/HarfBuzz for correct Arabic shaping in the synthetic corpus (WeasyPrint); OFL fonts only.
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      libpango-1.0-0 libpangoft2-1.0-0 libharfbuzz0b libgl1 libglib2.0-0 libgomp1 \
      fonts-hosny-amiri fonts-noto-core \
      tesseract-ocr tesseract-ocr-ara tesseract-ocr-eng \
 && rm -rf /var/lib/apt/lists/*

# paddlepaddle 3.2.2, not 3.3.1: 3.3.1 fails on CPU with oneDNN enabled ("ConvertPirAttribute2RuntimeAttribute not
# support pir::ArrayAttribute<pir::DoubleAttribute>"), and without oneDNN it is ~15x slower. See benchmarks/ocr/README.md.
RUN pip install \
      paddlepaddle==3.2.2 \
      paddleocr==3.7.0 \
      paddlex==3.7.2 \
      weasyprint==70.0 \
      pypdfium2==5.15.2 \
      rapidfuzz==3.14.6 \
      onnxruntime==1.31.1 \
      numpy pillow psutil

# Bake the candidate models into the image so benchmark runs need no network.
COPY fetch_models.py /opt/bench/fetch_models.py
RUN python /opt/bench/fetch_models.py

RUN useradd --create-home --uid 10001 bench && chown -R bench /opt/paddlex
USER bench
WORKDIR /opt/bench
COPY --chown=bench *.py /opt/bench/
