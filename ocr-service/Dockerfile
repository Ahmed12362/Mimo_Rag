# MimoRag OCR service (M2 slice 2d). Independently deployable: page images in, recognised lines out.
#   docker build -t mimorag-ocr:2d ocr-service                 # runtime image
#   docker build --target test ocr-service                     # runs the unit and API tests in the same environment
FROM python:3.11.11-slim-bookworm AS runtime

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK=True \
    PADDLE_PDX_CACHE_HOME=/opt/paddlex \
    HOME=/tmp

RUN apt-get update \
 && apt-get install -y --no-install-recommends libgl1 libglib2.0-0 libgomp1 \
      tesseract-ocr tesseract-ocr-ara tesseract-ocr-eng \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/ocr
COPY requirements.lock.txt ./
RUN pip install --no-deps -r requirements.lock.txt

# Only the models of the selected configuration, baked in: the service never downloads anything at run time.
COPY fetch_models.py ./
RUN python fetch_models.py && chmod -R a+rX /opt/paddlex

RUN useradd --uid 10001 --no-create-home --shell /usr/sbin/nologin ocr
COPY app ./app
USER 10001
EXPOSE 8090
HEALTHCHECK --interval=15s --timeout=3s --start-period=180s --retries=3 \
  CMD python -c "import sys,urllib.request; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8090/health/ready', timeout=2).status == 200 else 1)"
CMD ["uvicorn", "app.main:build", "--factory", "--host", "0.0.0.0", "--port", "8090", "--workers", "1", \
     "--no-server-header", "--no-access-log"]

FROM runtime AS test
USER root
COPY requirements-dev.txt ./
RUN pip install -r requirements-dev.txt
COPY tests ./tests
USER 10001
RUN python -m pytest -q -p no:cacheprovider tests
