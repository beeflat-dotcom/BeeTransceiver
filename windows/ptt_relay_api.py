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

PTT_RELAY_VERSION = "2026-10-22-ptt-ws-01"
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
    queue: asyncio.Queue[bytes] = field(default_factory=lambda: asyncio.Queue(maxsize=16))
    sender_task: asyncio.Task | None = None
    window: float = field(default_factory=time.monotonic)
    count: int = 0

    def allowed(self) -> bool:
        now = time.monotonic()
        if now - self.window > 1.0:
            self.window, self.count = now, 0
        self.count += 1
        return self.count <= MAX_PACKETS_PER_SECOND


class _Relay:
    def __init__(self):
        self.clients: set[_Client] = set()
        self.lock = asyncio.Lock()

    async def join(self, client: _Client) -> bool:
        async with self.lock:
            if len(self.clients) >= MAX_CLIENTS:
                return False
            self.clients.add(client)
            return True

    async def leave(self, client: _Client) -> None:
        async with self.lock:
            self.clients.discard(client)

    async def broadcast(self, source: _Client, data: bytes) -> None:
        async with self.lock:
            destinations = [peer for peer in self.clients if peer is not source]
        for peer in destinations:
            # Keep only recent audio to prevent delayed playback after congestion.
            if peer.queue.full():
                try:
                    peer.queue.get_nowait()
                except asyncio.QueueEmpty:
                    pass
            try:
                peer.queue.put_nowait(data)
            except asyncio.QueueFull:
                pass


async def _writer(peer: _Client) -> None:
    while True:
        packet = await peer.queue.get()
        await peer.websocket.send_bytes(packet)


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

    @app.get("/ptt/health")
    async def ptt_health():
        return {
            "ok": True, "version": PTT_RELAY_VERSION,
            "auth_configured": bool(token_provider()),
            "connected_devices": len(relay.clients),
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
        token = token_provider().strip()
        supplied = websocket.headers.get("authorization", "")
        if (not token or not supplied.startswith("Bearer ")
                or not hmac.compare_digest(token, supplied[7:].strip())):
            await websocket.close(code=1008)
            return
        client = _Client(websocket)
        if not await relay.join(client):
            await websocket.close(code=1013)
            return
        try:
            await websocket.accept()
            client.sender_task = asyncio.create_task(_writer(client))
            while True:
                message = await websocket.receive()
                if message.get("type") == "websocket.disconnect":
                    break
                data = message.get("bytes")
                if data is None:
                    continue
                if not client.allowed():
                    await websocket.close(code=1008)
                    break
                if _valid_packet(data):
                    await relay.broadcast(client, bytes(data))
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

