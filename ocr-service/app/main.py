"""HTTP layer of the OCR service: authentication, limits, problem responses. Contract: docs/adr/0014-ocr-service.md.

Logs carry the request id, sizes, timings and counts, never recognised text.
"""
import hmac
import json
import logging
import re
import time
import uuid
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import JSONResponse

from . import CONTRACT_VERSION
from .config import Settings
from .png import ImageRejected, check_limits, read_header
from .pool import BadImage, EngineError, NotReady, OcrTimeout, ServiceBusy, WorkerPool

log = logging.getLogger("ocr.http")
REQUEST_ID = re.compile(r"[A-Za-z0-9._-]{8,64}")
ORIENTATIONS = {"auto", "off"}
PROFILES = {"ar-en"}
BUSY_RETRY_AFTER = "5"


def problem(status: int, error_code: str, detail: str, request_id: str | None, headers: dict | None = None):
    body = {"type": "urn:mimorag:ocr:problem:" + error_code.lower().replace("_", "-"), "title": _TITLES.get(status, "Error"),
            "status": status, "detail": detail, "errorCode": error_code, "requestId": request_id}
    return JSONResponse(body, status_code=status, headers=headers, media_type="application/problem+json")


_TITLES = {400: "Bad Request", 401: "Unauthorized", 413: "Content Too Large", 415: "Unsupported Media Type",
           500: "Internal Server Error", 503: "Service Unavailable"}


def create_app(settings: Settings, pool: WorkerPool) -> FastAPI:

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        pool.start()
        yield
        pool.close()

    app = FastAPI(title="MimoRag OCR service", version=CONTRACT_VERSION, lifespan=lifespan, docs_url=None,
                  redoc_url=None, openapi_url=None)

    def authorised(request: Request) -> bool:
        presented = request.headers.get("x-ocr-token", "")
        return hmac.compare_digest(presented.encode(), settings.token.encode())

    def request_id_of(request: Request) -> str:
        candidate = request.headers.get("x-request-id", "")
        return candidate if REQUEST_ID.fullmatch(candidate) else uuid.uuid4().hex

    @app.get("/health/live")
    def live():
        return {"status": "UP"}

    @app.get("/health/ready")
    def ready():
        if pool.ready():
            return {"status": "UP"}
        return JSONResponse({"status": "DOWN"}, status_code=503)

    @app.get("/v1/info")
    def info(request: Request):
        rid = request_id_of(request)
        if not authorised(request):
            return problem(401, "AUTHENTICATION_REQUIRED", "A valid service token is required.", rid)
        return {"contractVersion": CONTRACT_VERSION, "engine": pool.versions,
                "limits": {"maxBodyBytes": settings.max_body_bytes, "maxSide": settings.max_side,
                           "maxPixels": settings.max_pixels, "minSide": settings.min_side,
                           "timeoutSeconds": settings.timeout_seconds, "processes": settings.processes,
                           "threadsPerProcess": settings.threads, "queueSize": settings.queue_size}}

    @app.post("/v1/ocr")
    async def ocr(request: Request):
        started = time.perf_counter()
        rid = request_id_of(request)
        if not authorised(request):
            log.warning(json.dumps({"event": "rejected", "requestId": rid, "reason": "AUTHENTICATION_REQUIRED"}))
            return problem(401, "AUTHENTICATION_REQUIRED", "A valid service token is required.", rid)
        if request.headers.get("content-type", "").split(";")[0].strip().lower() != "image/png":
            return problem(415, "UNSUPPORTED_MEDIA_TYPE", "Send the image as image/png.", rid)
        orientation = request.headers.get("x-ocr-orientation", "auto")
        profile = request.headers.get("x-ocr-profile", "ar-en")
        if orientation not in ORIENTATIONS or profile not in PROFILES:
            return problem(400, "INVALID_OPTIONS", "Unknown orientation mode or profile.", rid)

        declared = request.headers.get("content-length")
        if declared is not None and declared.isdigit() and int(declared) > settings.max_body_bytes:
            return problem(413, "IMAGE_TOO_LARGE", f"The body must be at most {settings.max_body_bytes} bytes.", rid)
        body = bytearray()
        async for chunk in request.stream():
            body.extend(chunk)
            if len(body) > settings.max_body_bytes:
                return problem(413, "IMAGE_TOO_LARGE", f"The body must be at most {settings.max_body_bytes} bytes.",
                               rid)
        try:
            header = read_header(bytes(body))
            check_limits(header, min_side=settings.min_side, max_side=settings.max_side,
                         max_pixels=settings.max_pixels)
        except ImageRejected as rejected:
            return problem(rejected.status, rejected.error_code, rejected.detail, rid)

        try:
            result = await run_in_threadpool(pool.run, bytes(body), orientation)
        except ServiceBusy:
            return problem(503, "SERVICE_BUSY", "The OCR service is busy. Retry later.", rid,
                           {"Retry-After": BUSY_RETRY_AFTER})
        except NotReady:
            return problem(503, "NOT_READY", "The OCR models are still loading.", rid, {"Retry-After": "10"})
        except OcrTimeout:
            return problem(503, "OCR_TIMEOUT", "Recognition took too long.", rid)
        except BadImage:
            return problem(400, "INVALID_IMAGE", "The PNG could not be decoded.", rid)
        except EngineError:
            return problem(500, "OCR_ENGINE_ERROR", "Recognition failed.", rid)

        log.info(json.dumps({"event": "ocr", "requestId": rid, "bytes": len(body), "width": header.width,
                             "height": header.height, "lines": result["summary"]["lines"],
                             "orientation": result["orientation"]["applied"],
                             "totalMs": int((time.perf_counter() - started) * 1000)}))
        return {"contractVersion": CONTRACT_VERSION, "requestId": rid, **result}

    return app


def build() -> FastAPI:
    """Entry point for uvicorn (`app.main:build --factory`)."""
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    settings = Settings.from_env()
    pool = WorkerPool(settings.processes, settings.threads, settings.queue_size, settings.timeout_seconds,
                      settings.recycle_after, settings.low_confidence)
    return create_app(settings, pool)
