"""A worker process without models, for testing the HTTP layer and the pool. Behaviour is chosen by a marker that
tests append to the (header-only) PNG bytes."""
import os
import struct
import time
import zlib

RESULT = {
    "engine": {"pipeline": "fake"},
    "image": {"width": 32, "height": 32},
    "orientation": {"checked": False, "applied": 0, "classifierConfidence": None},
    "lines": [{"box": {"x": 1, "y": 2, "width": 20, "height": 8}, "direction": "RTL", "confidence": 0.95,
               "originalText": "نص", "proposedText": "نص", "recoveredTokens": [], "unplacedTokens": [],
               "possiblyMissingTokens": [], "flags": [], "row": 0}],
    "summary": {"lines": 1, "characters": 2, "meanConfidence": 0.95, "lowConfidenceLines": 0, "recoveredTokens": 0,
                "unplacedTokens": 0, "possiblyMissingTokens": 0},
    "warnings": [],
    "timings": {"orientationMs": 0, "ocrMs": 1, "gateMs": 0, "tesseractMs": 0, "totalMs": 1},
}


def fake_worker(conn, threads, low_confidence):
    conn.send(("ready", {"pipeline": "fake", "threads": threads}))
    while True:
        message = conn.recv()
        if message is None:
            return
        png, orientation = message
        if b"SLEEP" in png:
            time.sleep(10)
        if b"CRASH" in png:
            os._exit(3)
        if b"BADIMAGE" in png:
            conn.send(("bad_image", "undecodable"))
            continue
        conn.send(("ok", dict(RESULT, orientation={**RESULT["orientation"], "mode": orientation})))


def png(width=32, height=32, marker=b""):
    """A PNG signature and IHDR chunk only: enough for the header checks (the fake worker never decodes)."""
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    chunk = struct.pack(">I", len(ihdr)) + b"IHDR" + ihdr + struct.pack(">I", zlib.crc32(b"IHDR" + ihdr))
    return b"\x89PNG\r\n\x1a\n" + chunk + marker
