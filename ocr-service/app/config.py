"""Service settings, read once from the environment. Every limit has a safe default."""
import os
from dataclasses import dataclass


def _int(name: str, default: int) -> int:
    value = int(os.environ.get(name, default))
    if value < 0:
        raise ValueError(f"{name} must not be negative")
    return value


def _float(name: str, default: float) -> float:
    return float(os.environ.get(name, default))


@dataclass(frozen=True)
class Settings:
    token: str
    processes: int
    threads: int
    queue_size: int
    timeout_seconds: float
    max_body_bytes: int
    max_side: int
    max_pixels: int
    min_side: int
    recycle_after: int
    low_confidence: float

    @staticmethod
    def from_env() -> "Settings":
        token = os.environ.get("OCR_TOKEN", "")
        if len(token) < 32:
            # A short or missing token would make the service usable by anything that can reach it.
            raise ValueError("OCR_TOKEN must be set to a secret of at least 32 characters")
        settings = Settings(
            token=token,
            processes=_int("OCR_PROCESSES", 1),
            threads=_int("OCR_THREADS", 4),
            queue_size=_int("OCR_QUEUE_SIZE", 2),
            timeout_seconds=_float("OCR_TIMEOUT_SECONDS", 60.0),
            max_body_bytes=_int("OCR_MAX_BODY_BYTES", 30 * 1024 * 1024),
            max_side=_int("OCR_MAX_SIDE", 10_000),
            max_pixels=_int("OCR_MAX_PIXELS", 40_000_000),
            min_side=_int("OCR_MIN_SIDE", 16),
            recycle_after=_int("OCR_RECYCLE_AFTER", 500),
            low_confidence=_float("OCR_LOW_CONFIDENCE", 0.80),
        )
        if settings.processes < 1 or settings.threads < 1:
            raise ValueError("OCR_PROCESSES and OCR_THREADS must be at least 1")
        return settings
