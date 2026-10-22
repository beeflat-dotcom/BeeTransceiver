# -*- coding: utf-8 -*-
"""Bee Transceiver audio upload API.

Receives complete M4A segments from BeeTransceiver and atomically publishes them
into Dropbox/AI用/録音, where WATCH ears already monitors them.
"""
from __future__ import annotations

import base64
import hashlib
import json
import os
import socket
import threading
import time
import uuid
from datetime import datetime
from pathlib import Path
from typing import Optional

from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse

VERSION = "2026/10/16-audio-upload-01"
MAX_UPLOAD_BYTES = 5_500_000
DISCOVERY_PORT = 45455
DISCOVERY_QUERY = b"BEE_AUDIO_DISCOVER_V1:"
DISCOVERY_REPLY = "BEE_AUDIO_SERVER_V1"

_DISCOVERY_LOCK = threading.Lock()
_DISCOVERY_STARTED = False


def _safe_log(message: str) -> None:
    line = f"[{datetime.now():%Y-%m-%d %H:%M:%S}] {message}"
    print(line, flush=True)
    try:
        path = Path(r"C:\BeeLocal\logs\audio_upload_api.log")
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("a", encoding="utf-8") as f:
            f.write(line + "\n")
    except Exception:
        pass


def _read_token(token_file: Path) -> str:
    env = os.environ.get("BEE_AUDIO_UPLOAD_TOKEN", "").strip()
    if env:
        return env
    try:
        return token_file.read_text(encoding="utf-8-sig").strip()
    except Exception:
        return ""


def _decode_filename(value: str) -> str:
    if not value:
        raise HTTPException(status_code=400, detail="missing filename")
    try:
        raw = value.encode("ascii")
        raw += b"=" * ((4 - len(raw) % 4) % 4)
        name = base64.urlsafe_b64decode(raw).decode("utf-8")
    except Exception as exc:
        raise HTTPException(status_code=400, detail="invalid filename encoding") from exc
    if not name or len(name) > 180:
        raise HTTPException(status_code=400, detail="invalid filename")
    if Path(name).name != name or name in {".", ".."}:
        raise HTTPException(status_code=400, detail="invalid filename")
    if not name.lower().endswith(".m4a"):
        raise HTTPException(status_code=400, detail="only m4a accepted")
    return name




def _decode_optional_b64_text(value: str, max_chars: int) -> str:
    if not value:
        return ""
    try:
        raw = value.encode("ascii")
        raw += b"=" * ((4 - len(raw) % 4) % 4)
        return base64.urlsafe_b64decode(raw).decode("utf-8", errors="replace")[:max_chars]
    except Exception:
        return ""

def _sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        while True:
            chunk = f.read(1024 * 1024)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


def _start_discovery(port: int = DISCOVERY_PORT) -> None:
    global _DISCOVERY_STARTED
    with _DISCOVERY_LOCK:
        if _DISCOVERY_STARTED:
            return
        _DISCOVERY_STARTED = True

    def run() -> None:
        while True:
            sock: Optional[socket.socket] = None
            try:
                sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                sock.bind(("0.0.0.0", port))
                while True:
                    data, addr = sock.recvfrom(512)
                    if not data.startswith(DISCOVERY_QUERY):
                        continue
                    nonce = data[len(DISCOVERY_QUERY):].decode("ascii", errors="ignore")[:80]
                    reply = f"{DISCOVERY_REPLY}:{nonce}:8769".encode("ascii")
                    sock.sendto(reply, addr)
            except Exception as exc:
                _safe_log(f"discovery error: {type(exc).__name__}: {exc}")
                time.sleep(5)
            finally:
                if sock is not None:
                    try:
                        sock.close()
                    except Exception:
                        pass

    threading.Thread(target=run, daemon=True, name="BeeAudioDiscovery").start()


class AudioUploadStore:
    def __init__(self, target_dir: Path, token_file: Path, max_bytes: int = MAX_UPLOAD_BYTES):
        self.target_dir = target_dir
        self.incoming_dir = target_dir / ".incoming"
        self.token_file = token_file
        self.max_bytes = max_bytes

    def token(self) -> str:
        return _read_token(self.token_file)

    def authorize(self, authorization: str | None) -> None:
        token = self.token()
        if not token:
            raise HTTPException(status_code=503, detail="audio upload token not configured")
        prefix = "Bearer "
        if not authorization or not authorization.startswith(prefix):
            raise HTTPException(status_code=401, detail="authorization required")
        supplied = authorization[len(prefix):].strip()
        # Constant-time compare via digest to avoid accidental timing leaks.
        if not hashlib.sha256(supplied.encode()).digest() == hashlib.sha256(token.encode()).digest():
            raise HTTPException(status_code=403, detail="invalid token")

    async def receive(
        self,
        request: Request,
        filename_b64: str,
        expected_sha256: str,
        upload_id: str,
        device: str,
    ) -> dict:
        name = _decode_filename(filename_b64)
        expected_sha256 = (expected_sha256 or "").lower().strip()
        if len(expected_sha256) != 64 or any(c not in "0123456789abcdef" for c in expected_sha256):
            raise HTTPException(status_code=400, detail="invalid sha256")
        if upload_id and len(upload_id) > 100:
            raise HTTPException(status_code=400, detail="invalid upload id")

        self.target_dir.mkdir(parents=True, exist_ok=True)
        self.incoming_dir.mkdir(parents=True, exist_ok=True)
        destination = self.target_dir / name

        if destination.exists():
            size = destination.stat().st_size
            digest = _sha256_file(destination)
            if digest == expected_sha256:
                _safe_log(f"duplicate accepted file={name} size={size} device={device}")
                return {
                    "ok": True,
                    "status": "already_present",
                    "file": name,
                    "size": size,
                    "sha256": digest,
                    "upload_id": upload_id,
                }
            raise HTTPException(status_code=409, detail="filename already exists with different content")

        temp = self.incoming_dir / f"{uuid.uuid4().hex}.part"
        h = hashlib.sha256()
        total = 0
        try:
            with temp.open("wb") as f:
                async for chunk in request.stream():
                    if not chunk:
                        continue
                    total += len(chunk)
                    if total > self.max_bytes:
                        raise HTTPException(status_code=413, detail="audio segment too large")
                    h.update(chunk)
                    f.write(chunk)
                f.flush()
                os.fsync(f.fileno())

            if total < 512:
                raise HTTPException(status_code=400, detail="audio segment too small")
            digest = h.hexdigest()
            if digest != expected_sha256:
                raise HTTPException(status_code=422, detail="sha256 mismatch")

            # Atomic publish in the same Dropbox filesystem. WATCH ignores .incoming/*.part.
            os.replace(temp, destination)
            _safe_log(f"stored file={name} size={total} sha256={digest[:12]} device={device} upload={upload_id}")
            return {
                "ok": True,
                "status": "stored",
                "file": name,
                "size": total,
                "sha256": digest,
                "upload_id": upload_id,
            }
        finally:
            try:
                temp.unlink(missing_ok=True)
            except Exception:
                pass


def install_audio_upload_api(
    app: FastAPI,
    target_dir: Path | None = None,
    token_file: Path | None = None,
) -> AudioUploadStore:
    here = Path(__file__).resolve().parent
    target_dir = target_dir or (Path.home() / "Dropbox" / "AI用" / "録音")
    token_file = token_file or (here / "audio_upload_token.txt")
    store = AudioUploadStore(Path(target_dir), Path(token_file))
    _start_discovery()

    @app.get("/audio-upload/health")
    def audio_upload_health():
        return {
            "ok": True,
            "version": VERSION,
            "auth_configured": bool(store.token()),
            "max_upload_bytes": store.max_bytes,
            "discovery_port": DISCOVERY_PORT,
            "target_ready": store.target_dir.parent.exists(),
        }

    @app.post("/audio-upload")
    async def audio_upload(
        request: Request,
        authorization: str | None = Header(default=None),
        x_bee_filename_b64: str | None = Header(default=None),
        x_bee_sha256: str | None = Header(default=None),
        x_bee_upload_id: str | None = Header(default=""),
        x_bee_device_b64: str | None = Header(default=""),
    ):
        store.authorize(authorization)
        result = await store.receive(
            request,
            x_bee_filename_b64 or "",
            x_bee_sha256 or "",
            x_bee_upload_id or "",
            _decode_optional_b64_text(x_bee_device_b64 or "", 120),
        )
        return JSONResponse(result)

    return store

