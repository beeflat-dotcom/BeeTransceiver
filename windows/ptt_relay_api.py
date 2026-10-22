# -*- coding: utf-8 -*-
"""BeeTransceiver internet PTT relay: short-lived pairing and in-memory audio.

Install on the existing FastAPI Windows server behind Cloudflare Tunnel.
"""
from __future__ import annotations

import asyncio
import hashlib
import hmac
import json
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

from fastapi import FastAPI, HTTPException, Request, WebSocket, WebSocketDisconnect

PTT_RELAY_VERSION = "2026-10-22-ptt-floor-v02"
NEW_CLIENT_LEASE_SECONDS = 4.0
LEGACY_CLIENT_LEASE_SECONDS = 1.2
MAX_CLIENTS = 32
MAX_PACKET = 1450
MAX_PACKETS_PER_SECOND = 85
PAIRING_TTL_SECONDS = 180


def _valid_packet(packet: bytes) -> bool:
    if len(packet) < 30 or len(packet) > MAX_PACKET or packet[:5] != b"BPT1\x01":
        return False
    name_len = packet[25]
    offset = 26 + name_len
    if offset + 2 >= len(packet):
        return False
    audio_size = int.from_bytes(packet[offset:offset + 2], "big")
    return 0 < audio_size <= 1024 and offset + 2 + audio_size == len(packet)


@dataclass(eq=False)
class _Client:
    websocket: WebSocket
    queue: asyncio.Queue[tuple[str, bytes | str]] = field(default_factory=lambda: asyncio.Queue(maxsize=24))
    managed: bool = False
    sender_task: asyncio.Task | None = None
    window: float = field(default_factory=time.monotonic)
    count: int = 0

    def enqueue(self, kind: str, payload: bytes | str) -> None:
        if self.queue.full():
            try:
                self.queue.get_nowait()
            except asyncio.QueueEmpty:
                pass
        try:
            self.queue.put_nowait((kind, payload))
        except asyncio.QueueFull:
            pass

    def control(self, message: dict) -> None:
        self.enqueue("text", json.dumps(message, ensure_ascii=False))

    def allowed(self) -> bool:
        now = time.monotonic()
        if now - self.window > 1.0:
            self.window, self.count = now, 0
        self.count += 1
        return self.count <= MAX_PACKETS_PER_SECOND


def _clean_name(raw: object) -> str:
    if not isinstance(raw, str):
        return "端末"
    raw = "".join(c for c in raw.strip() if c.isprintable()) or "端末"
    while len(raw.encode("utf-8")) > 48:
        raw = raw[:-1]
    return raw or "端末"


class _Relay:
    def __init__(self):
        self.clients: set[_Client] = set()
        self.lock = asyncio.Lock()
        self.holder: _Client | None = None
        self.holder_name = ""
        self.last_audio = 0.0

    def _publish_state_locked(self) -> None:
        for peer in self.clients:
            peer.control({"type": "ptt_state", "active": self.holder is not None,
                          "name": self.holder_name if self.holder else "",
                          "mine": self.holder is peer})

    def _expire_locked(self, now: float) -> None:
        if self.holder is None:
            return
        limit = NEW_CLIENT_LEASE_SECONDS if self.holder.managed else LEGACY_CLIENT_LEASE_SECONDS
        if now - self.last_audio > limit:
            self.holder = None
            self.holder_name = ""
            self.last_audio = 0.0
            self._publish_state_locked()

    async def join(self, client: _Client) -> bool:
        async with self.lock:
            if len(self.clients) >= MAX_CLIENTS:
                return False
            self.clients.add(client)
            client.control({"type": "hello_ack", "protocol": 2, "floor_control": True})
            client.control({"type": "ptt_state", "active": self.holder is not None,
                            "name": self.holder_name if self.holder else "", "mine": False})
            return True

    async def leave(self, client: _Client) -> None:
        async with self.lock:
            self.clients.discard(client)
            if self.holder is client:
                self.holder = None
                self.holder_name = ""
                self.last_audio = 0.0
                self._publish_state_locked()

    async def request(self, client: _Client, name: object) -> None:
        async with self.lock:
            now = time.monotonic()
            self._expire_locked(now)
            client.managed = True
            if self.holder is None or self.holder is client:
                self.holder = client
                self.holder_name = _clean_name(name)
                self.last_audio = now
                client.control({"type": "ptt_granted", "name": self.holder_name})
                self._publish_state_locked()
            else:
                client.control({"type": "ptt_busy", "name": self.holder_name})

    async def release(self, client: _Client) -> None:
        async with self.lock:
            if self.holder is client:
                self.holder = None
                self.holder_name = ""
                self.last_audio = 0.0
                self._publish_state_locked()

    async def receive_audio(self, client: _Client, data: bytes) -> None:
        if not _valid_packet(data):
            return
        async with self.lock:
            now = time.monotonic()
            self._expire_locked(now)
            if self.holder is None and not client.managed:
                # v0.7 legacy devices claim the floor with their first audio packet.
                self.holder = client
                length = data[25]
                self.holder_name = _clean_name(data[26:26 + length].decode("utf-8", errors="replace"))
                self.last_audio = now
                self._publish_state_locked()
            if self.holder is not client:
                return
            self.last_audio = now
            for peer in self.clients:
                if peer is not client:
                    peer.enqueue("bytes", bytes(data))

    async def expire(self) -> bool:
        async with self.lock:
            if not self.clients:
                return False
            self._expire_locked(time.monotonic())
            return True


async def _writer(peer: _Client) -> None:
    while True:
        kind, packet = await peer.queue.get()
        if kind == "text":
            await peer.websocket.send_text(packet)
        else:
            await peer.websocket.send_bytes(packet)


async def _lease_reaper(relay: _Relay) -> None:
    while True:
        await asyncio.sleep(0.5)
        if not await relay.expire():
            return


def install_ptt_relay_api(
    app: FastAPI, token_provider: Callable[[], str], pairing_file: Path | None = None
) -> _Relay:
    """Add relay routes using the existing audio-upload token provider."""
    here = Path(__file__).resolve().parent
    pairing_file = Path(pairing_file or here / "ptt_pairing_code.json")
    relay = _Relay()
    pairing_lock = asyncio.Lock()
    failures: dict[str, tuple[float, int]] = {}
    global_failures: tuple[float, int] = (0.0, 0)
    reaper_task: asyncio.Task | None = None

    @app.get("/ptt/health")
    async def ptt_health():
        return {
            "ok": True, "version": PTT_RELAY_VERSION,
            "auth_configured": bool(token_provider()),
            "connected_devices": len(relay.clients),
            "floor_control": True,
            "speaker_active": relay.holder is not None,
            "websocket": "/ptt/ws", "pairing": "/ptt/pair",
        }

    @app.post("/ptt/pair")
    async def ptt_pair(request: Request):
        nonlocal global_failures
        ip = request.client.host if request.client else "unknown"
        now = time.monotonic()
        async with pairing_lock:
            start, count = failures.get(ip, (now, 0))
            if now - start > PAIRING_TTL_SECONDS:
                start, count = now, 0
            gstart, gcount = global_failures
            if now - gstart > PAIRING_TTL_SECONDS:
                gstart, gcount = now, 0
            if count >= 8 or gcount >= 40:
                raise HTTPException(status_code=429, detail="too many pairing attempts")
            failures[ip] = (start, count + 1)
            global_failures = (gstart, gcount + 1)
            try:
                body = await request.json()
                code = body.get("code", "") if isinstance(body, dict) else ""
                if not isinstance(code, str) or len(code) != 6 or not code.isdecimal():
                    raise HTTPException(status_code=400, detail="six digit code required")
            except HTTPException:
                raise
            except Exception as exc:
                raise HTTPException(status_code=400, detail="invalid json") from exc
            try:
                data = json.loads(pairing_file.read_text(encoding="utf-8"))
            except (FileNotFoundError, OSError, ValueError) as exc:
                raise HTTPException(status_code=410, detail="pairing code not active") from exc
            if time.time() > float(data.get("expires_at", 0)):
                raise HTTPException(status_code=410, detail="pairing code expired")
            submitted = hashlib.sha256(code.encode("ascii")).hexdigest()
            if not hmac.compare_digest(str(data.get("code_sha256", "")), submitted):
                raise HTTPException(status_code=403, detail="incorrect pairing code")
            token = token_provider().strip()
            if not token:
                raise HTTPException(status_code=503, detail="server token not configured")
            try:
                pairing_file.unlink(missing_ok=True)
            except OSError:
                raise HTTPException(status_code=500, detail="could not consume pairing code")
            return {"ok": True, "token": token, "version": PTT_RELAY_VERSION}

    @app.websocket("/ptt/ws")
    async def ptt_websocket(websocket: WebSocket):
        nonlocal reaper_task
        token = token_provider().strip()
        supplied = websocket.headers.get("authorization", "")
        if (not token or not supplied.startswith("Bearer ")
                or not hmac.compare_digest(token, supplied[7:].strip())):
            await websocket.close(code=1008)
            return
        client = _Client(websocket)
        await websocket.accept()
        if not await relay.join(client):
            await websocket.close(code=1013)
            return
        try:
            client.sender_task = asyncio.create_task(_writer(client))
            if reaper_task is None or reaper_task.done():
                reaper_task = asyncio.create_task(_lease_reaper(relay))
            while True:
                message = await websocket.receive()
                if message.get("type") == "websocket.disconnect":
                    break
                data = message.get("bytes")
                if data is not None:
                    if not client.allowed():
                        await websocket.close(code=1008)
                        break
                    await relay.receive_audio(client, data)
                    continue
                body = message.get("text")
                if not isinstance(body, str) or len(body) > 512:
                    continue
                try:
                    control = json.loads(body)
                except (TypeError, ValueError):
                    continue
                if not isinstance(control, dict):
                    continue
                kind = control.get("type")
                if kind == "ptt_request":
                    await relay.request(client, control.get("name", "端末"))
                elif kind == "ptt_release":
                    await relay.release(client)
                elif kind == "hello":
                    client.managed = True
                    client.control({"type": "hello_ack", "protocol": 2, "floor_control": True})
        except (WebSocketDisconnect, RuntimeError, OSError):
            pass
        finally:
            await relay.leave(client)
            if client.sender_task is not None:
                client.sender_task.cancel()
                try:
                    await client.sender_task
                except asyncio.CancelledError:
                    pass

    return relay

