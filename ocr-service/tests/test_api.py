"""HTTP contract and pool behaviour, with the fake worker process (no models)."""
import threading
import time

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app
from app.pool import WorkerPool
from tests.fake_worker import fake_worker, png

TOKEN = "t" * 40


def settings(**overrides) -> Settings:
    values = dict(token=TOKEN, processes=1, threads=4, queue_size=0, timeout_seconds=2.0, max_body_bytes=1024 * 1024,
                  max_side=10_000, max_pixels=40_000_000, min_side=16, recycle_after=500, low_confidence=0.8)
    values.update(overrides)
    return Settings(**values)


@pytest.fixture
def client_factory():
    clients = []

    def make(**overrides):
        s = settings(**overrides)
        pool = WorkerPool(s.processes, s.threads, s.queue_size, s.timeout_seconds, s.recycle_after,
                          s.low_confidence, target=fake_worker, start_timeout=30)
        client = TestClient(create_app(s, pool))
        client.__enter__()
        clients.append(client)
        deadline = time.time() + 30
        while client.get("/health/ready").status_code != 200 and time.time() < deadline:
            time.sleep(0.1)
        return client

    yield make
    for client in clients:
        client.__exit__(None, None, None)


def post(client, body, token=TOKEN, content_type="image/png", **headers):
    return client.post("/v1/ocr", content=body, headers={"X-OCR-Token": token, "Content-Type": content_type,
                                                         "X-Request-Id": "req-12345678", **headers})


def test_valid_request_returns_the_contract(client_factory):
    response = post(client_factory(), png())

    assert response.status_code == 200
    body = response.json()
    assert body["contractVersion"] == "1"
    assert body["requestId"] == "req-12345678"
    assert {"engine", "image", "orientation", "lines", "summary", "warnings", "timings"} <= body.keys()


@pytest.mark.parametrize("token", ["", "wrong", "t" * 39, "t" * 41])
def test_every_request_needs_the_exact_token(client_factory, token):
    response = post(client_factory(), png(), token=token)

    assert response.status_code == 401
    assert response.json()["errorCode"] == "AUTHENTICATION_REQUIRED"
    assert response.headers["content-type"].startswith("application/problem+json")


def test_info_also_needs_the_token(client_factory):
    client = client_factory()
    assert client.get("/v1/info").status_code == 401
    assert client.get("/v1/info", headers={"X-OCR-Token": TOKEN}).json()["limits"]["queueSize"] == 0


def test_only_png_is_accepted(client_factory):
    client = client_factory()
    assert post(client, png(), content_type="application/pdf").status_code == 415
    assert post(client, b"%PDF-1.7 a pdf pretending", content_type="image/png").json()["errorCode"] == \
        "UNSUPPORTED_MEDIA_TYPE"


def test_size_limits(client_factory):
    client = client_factory(max_body_bytes=1000)
    assert post(client, png(marker=b"x" * 2000)).json()["errorCode"] == "IMAGE_TOO_LARGE"
    assert post(client, png(50_000, 50_000)).status_code == 413
    assert post(client, png(8, 8)).json()["errorCode"] == "INVALID_IMAGE"


def test_unknown_options_are_rejected(client_factory):
    client = client_factory()
    assert post(client, png(), **{"X-OCR-Orientation": "sideways"}).json()["errorCode"] == "INVALID_OPTIONS"
    assert post(client, png(), **{"X-OCR-Profile": "fr"}).json()["errorCode"] == "INVALID_OPTIONS"


def test_undecodable_image_is_a_bad_request(client_factory):
    assert post(client_factory(), png(marker=b"BADIMAGE")).json()["errorCode"] == "INVALID_IMAGE"


def test_busy_service_refuses_at_once_with_retry_after(client_factory):
    client = client_factory(timeout_seconds=5.0, queue_size=0)
    slow = threading.Thread(target=lambda: post(client, png(marker=b"SLEEP")))
    slow.start()
    time.sleep(0.5)
    response = post(client, png())
    slow.join()

    assert response.status_code == 503
    assert response.json()["errorCode"] == "SERVICE_BUSY"
    assert response.headers["retry-after"] == "5"


def test_timeout_replaces_the_worker_and_the_service_keeps_working(client_factory):
    client = client_factory(timeout_seconds=1.0)

    timed_out = post(client, png(marker=b"SLEEP"))
    after = post(client, png())

    assert timed_out.status_code == 503
    assert timed_out.json()["errorCode"] == "OCR_TIMEOUT"
    assert after.status_code == 200


def test_crashed_worker_is_replaced(client_factory):
    client = client_factory()

    crashed = post(client, png(marker=b"CRASH"))
    after = post(client, png())

    assert crashed.status_code == 500
    assert crashed.json()["errorCode"] == "OCR_ENGINE_ERROR"
    assert after.status_code == 200


def test_health_endpoints_need_no_token(client_factory):
    client = client_factory()
    assert client.get("/health/live").status_code == 200
    assert client.get("/health/ready").status_code == 200


def test_problem_responses_never_echo_the_body(client_factory):
    response = post(client_factory(), png(marker=b"BADIMAGE secret-content"))
    assert "secret-content" not in response.text


def test_settings_refuse_a_short_token(monkeypatch):
    monkeypatch.setenv("OCR_TOKEN", "short")
    with pytest.raises(ValueError):
        Settings.from_env()
