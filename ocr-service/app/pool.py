"""Worker processes and admission control.

* Each worker is a separate process with its own loaded models (OCR_PROCESSES of them, OCR_THREADS each). One request
  at a time per worker; at most OCR_QUEUE_SIZE more requests wait. Anything beyond is refused at once (503), so load
  turns into back-pressure, not memory growth.
* A request that exceeds the time budget gets its worker killed and replaced: a hung parse cannot block the service.
* Workers are replaced after OCR_RECYCLE_AFTER requests (guards against slow memory growth in native libraries).
"""
import logging
import multiprocessing
import queue
import threading
import time
from dataclasses import dataclass
from typing import Callable

log = logging.getLogger("ocr.pool")


class ServiceBusy(Exception):
    pass


class NotReady(Exception):
    pass


class OcrTimeout(Exception):
    pass


class EngineError(Exception):
    pass


class BadImage(Exception):
    pass


def engine_worker(conn, threads: int, low_confidence: float) -> None:
    """Body of a worker process: load the real engine, self-test, then serve requests until told to stop."""
    from .engine import InvalidImage, OcrEngine
    import cv2
    import numpy as np

    engine = OcrEngine(threads, low_confidence)
    ok, sample = cv2.imencode(".png", np.full((64, 256, 3), 255, dtype=np.uint8))
    engine.recognize(sample.tobytes(), "off")  # self-test: models load and run before the service reports ready
    conn.send(("ready", engine.versions))
    while True:
        message = conn.recv()
        if message is None:
            return
        png, orientation = message
        try:
            conn.send(("ok", engine.recognize(png, orientation)))
        except InvalidImage as ex:
            conn.send(("bad_image", str(ex)))
        except Exception:  # noqa: BLE001 - reported as a safe code; details only in the worker log
            log.exception("OCR engine failure")
            conn.send(("error", "OCR_ENGINE_ERROR"))


@dataclass
class _Worker:
    process: multiprocessing.Process
    conn: object
    served: int = 0


class WorkerPool:

    def __init__(self, processes: int, threads: int, queue_size: int, timeout_seconds: float, recycle_after: int,
                 low_confidence: float, target: Callable = engine_worker, start_timeout: float = 600.0):
        self._context = multiprocessing.get_context("spawn")
        self._processes = processes
        self._threads = threads
        self._timeout = timeout_seconds
        self._recycle_after = recycle_after
        self._low_confidence = low_confidence
        self._target = target
        self._start_timeout = start_timeout
        self._admission = threading.BoundedSemaphore(processes + queue_size)
        self._idle: queue.Queue[_Worker] = queue.Queue()
        self._ready = threading.Event()
        self._closed = False
        self.versions: dict = {}

    # ---- lifecycle ------------------------------------------------------------------------------------------------

    def start(self) -> None:
        """Starts the workers in the background; the service is ready when all of them passed their self-test."""
        def start_all():
            for _ in range(self._processes):
                worker = self._spawn()
                if worker is not None:
                    self._idle.put(worker)
            if self._idle.qsize() == self._processes:
                self._ready.set()
        threading.Thread(target=start_all, name="ocr-pool-start", daemon=True).start()

    def ready(self) -> bool:
        return self._ready.is_set() and not self._closed

    def close(self, grace_seconds: float = 60.0) -> None:
        self._closed = True
        deadline = time.monotonic() + grace_seconds
        stopped = 0
        while stopped < self._processes and time.monotonic() < deadline:
            try:
                worker = self._idle.get(timeout=max(0.1, deadline - time.monotonic()))
            except queue.Empty:
                break
            self._stop(worker)
            stopped += 1

    # ---- requests -------------------------------------------------------------------------------------------------

    def run(self, png: bytes, orientation: str) -> dict:
        if not self.ready():
            raise NotReady()
        if not self._admission.acquire(blocking=False):
            raise ServiceBusy()
        try:
            try:
                worker = self._idle.get(timeout=self._timeout)
            except queue.Empty as ex:
                raise ServiceBusy() from ex
            try:
                worker.conn.send((png, orientation))
                if not worker.conn.poll(self._timeout):
                    log.warning("OCR request exceeded %.0f s; replacing worker pid=%s", self._timeout,
                                worker.process.pid)
                    worker = self._replace(worker)
                    raise OcrTimeout()
                status, payload = worker.conn.recv()
                worker.served += 1
                if worker.served >= self._recycle_after:
                    worker = self._replace(worker)
            except (EOFError, OSError, BrokenPipeError) as ex:
                log.error("OCR worker pid=%s died; replacing it", worker.process.pid)
                worker = self._replace(worker)
                raise EngineError() from ex
            finally:
                if worker is not None:
                    self._idle.put(worker)
                elif not self._closed:
                    self._ready.clear()
        finally:
            self._admission.release()
        if status == "ok":
            return payload
        if status == "bad_image":
            raise BadImage(payload)
        raise EngineError(payload)

    # ---- worker management ----------------------------------------------------------------------------------------

    def _spawn(self) -> _Worker | None:
        parent, child = self._context.Pipe()
        process = self._context.Process(target=self._target, args=(child, self._threads, self._low_confidence),
                                        daemon=True, name="ocr-worker")
        process.start()
        if not parent.poll(self._start_timeout):
            log.error("OCR worker did not become ready in %.0f s", self._start_timeout)
            process.kill()
            return None
        status, payload = parent.recv()
        if status != "ready":
            process.kill()
            return None
        self.versions = payload
        return _Worker(process, parent)

    def _replace(self, worker: _Worker) -> _Worker | None:
        self._kill(worker)
        return self._spawn() if not self._closed else None

    def _stop(self, worker: _Worker) -> None:
        try:
            worker.conn.send(None)
            worker.process.join(5)
        except (OSError, BrokenPipeError):
            pass
        if worker.process.is_alive():
            self._kill(worker)

    @staticmethod
    def _kill(worker: _Worker) -> None:
        worker.process.kill()
        worker.process.join(5)
