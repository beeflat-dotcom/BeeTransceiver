# -*- coding: utf-8 -*-
"""BeeTransceiver internet PTT relay: short-lived pairing and in-memory audio.

Install on the existing FastAPI Windows server behind Cloudflare Tunnel.
"""
from __future__ import annotations

import asyncio
import io
import logging
import os
import hashlib
import hmac
import json
import struct
import time
import uuid
import wave
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

from fastapi import FastAPI, HTTPException, Request, WebSocket, WebSocketDisconnect

PTT_RELAY_VERSION = "2026-10-29-ptt-ws-05-realtime-pacing"
MAX_CLIENTS = 32
MAX_PACKET = 1450
MAX_PACKETS_PER_SECOND = 85
PAIRING_TTL_SECONDS = 180
SAMPLE_RATE = 16000
SAMPLES_PER_PACKET = 320
PACKET_AUDIO_BYTES = 640
PACKET_SECONDS = 0.020
MAX_WAV_BYTES = 32 * 1024 * 1024
SERVER_SENDER_ID = uuid.uuid5(uuid.NAMESPACE_DNS, "jp.beeflat.bee-ptt-server-playback")


def _build_server_packet(seq: int, device_name: str, audio: bytes) -> bytes:
    name = device_name.encode("utf-8")[:48]
    while name:
        try:
            name.decode("utf-8")
            break
        except UnicodeDecodeError:
            name = name[:-1]
    if not audio or len(audio) > 1024:
        raise ValueError("PTT audio payload must be 1..1024 bytes")
    return b"".join([
        b"BPT1", bytes([1]), SERVER_SENDER_ID.bytes,
        struct.pack(">I", seq & 0xFFFFFFFF),
        bytes([len(name)]), name,
        struct.pack(">H", len(audio)), audio,
    ])


# BEE_SERVER_WAV_PLAYBACK_V1


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
    queue: asyncio.Queue[bytes] = field(default_factory=lambda: asyncio.Queue(maxsize=96))
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
        self.playback_lock = asyncio.Lock()
        self.audio_dropped_packets = 0
        self.last_audio_playback = {}

    async def join(self, client: _Client) -> bool:
        async with self.lock:
            if len(self.clients) >= MAX_CLIENTS:
                return False
            self.clients.add(client)
            return True

    async def leave(self, client: _Client) -> None:
        async with self.lock:
            self.clients.discard(client)

    async def _enqueue(self, destinations: list[_Client], data: bytes) -> None:
        for peer in destinations:
            # Keep only recent audio to prevent delayed playback after congestion.
            if peer.queue.full():
                self.audio_dropped_packets += 1
                try:
                    peer.queue.get_nowait()
                except asyncio.QueueEmpty:
                    pass
            try:
                peer.queue.put_nowait(data)
            except asyncio.QueueFull:
                pass

    async def broadcast(self, source: _Client, data: bytes) -> None:
        async with self.lock:
            destinations = [peer for peer in self.clients if peer is not source]
        await self._enqueue(destinations, data)

    async def broadcast_all(self, data: bytes) -> None:
        async with self.lock:
            destinations = list(self.clients)
        await self._enqueue(destinations, data)


async def _writer(relay: _Relay, peer: _Client) -> None:
    try:
        while True:
            packet = await peer.queue.get()
            await peer.websocket.send_bytes(packet)
    except asyncio.CancelledError:
        raise
    except Exception:
        # Dead websocket: remove immediately so other devices keep receiving.
        await relay.leave(peer)


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
            "server_playback": True, "play_wav": "/ptt/play-wav",
            "audio_dropped_packets": relay.audio_dropped_packets,
            "last_audio_playback": relay.last_audio_playback,
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

    @app.post("/ptt/play-wav")
    async def ptt_play_wav(request: Request):
        """Receive a complete 16 kHz mono PCM WAV, then pace it to PTT clients."""
        token = token_provider().strip()
        supplied = request.headers.get("authorization", "")
        if (not token or not supplied.startswith("Bearer ")
                or not hmac.compare_digest(token, supplied[7:].strip())):
            raise HTTPException(status_code=403, detail="invalid token")
        if not relay.clients:
            raise HTTPException(status_code=409, detail="no PTT devices connected")

        body = await request.body()
        if not body or len(body) > MAX_WAV_BYTES:
            raise HTTPException(status_code=413, detail="WAV body missing or too large")

        device_name = request.query_params.get("device_name", "びーAI").strip() or "びーAI"
        try:
            pre_ms = max(0, min(2000, int(request.query_params.get("pre_ms", "180"))))
            post_ms = max(0, min(3000, int(request.query_params.get("post_ms", "260"))))
        except ValueError:
            raise HTTPException(status_code=400, detail="invalid silence duration")

        try:
            wf = wave.open(io.BytesIO(body), "rb")
        except (wave.Error, EOFError) as exc:
            raise HTTPException(status_code=400, detail="invalid WAV") from exc
        timer_requested = False
        if os.name == "nt":
            try:
                import ctypes
                timer_requested = ctypes.windll.winmm.timeBeginPeriod(1) == 0
            except Exception:
                pass
        try:
            if wf.getnchannels() != 1 or wf.getsampwidth() != 2 or wf.getframerate() != SAMPLE_RATE:
                raise HTTPException(status_code=400, detail="WAV must be 16 kHz mono 16-bit PCM")
            frame_count = wf.getnframes()
            if frame_count <= 0:
                raise HTTPException(status_code=400, detail="empty WAV")

            silence = b"\x00" * PACKET_AUDIO_BYTES
            pre_packets = (pre_ms + 19) // 20
            post_packets = (post_ms + 19) // 20

            async with relay.playback_lock:
                loop = asyncio.get_running_loop()
                next_at = loop.time()
                started_at = time.monotonic()
                initial_devices = len(relay.clients)
                dropped_before = relay.audio_dropped_packets
                peak_lag_ms = 0.0
                scheduler_stalls = 0
                seq = 0

                async def emit(audio: bytes) -> None:
                    nonlocal next_at, seq, peak_lag_ms, scheduler_stalls
                    now = loop.time()
                    lateness = max(0.0, now - next_at)
                    peak_lag_ms = max(peak_lag_ms, lateness * 1000.0)
                    if lateness > 0.040:
                        scheduler_stalls += 1
                        # Catch up by at most 60ms. Avoid both unlimited bursts
                        # and stretching the remainder of the WAV after a stall.
                        next_at = now
                    delay = next_at - now
                    if delay > 0:
                        await asyncio.sleep(delay)
                    await relay.broadcast_all(_build_server_packet(seq, device_name, audio))
                    seq += 1
                    next_at += PACKET_SECONDS

                for _ in range(pre_packets):
                    await emit(silence)

                while True:
                    audio = wf.readframes(SAMPLES_PER_PACKET)
                    if not audio:
                        break
                    if len(audio) < PACKET_AUDIO_BYTES:
                        audio += b"\x00" * (PACKET_AUDIO_BYTES - len(audio))
                    await emit(audio)

                for _ in range(post_packets):
                    await emit(silence)

                # Let websocket writer tasks flush the final queued packet.
                await asyncio.sleep(0.10)
                elapsed = time.monotonic() - started_at
                expected = seq * PACKET_SECONDS
                relay.last_audio_playback = {
                    "elapsed_seconds": round(elapsed, 2),
                    "audio_seconds": round(expected, 2),
                    "pacing_ratio": round(elapsed / expected, 2) if expected else 0,
                    "packets": seq,
                    "scheduler_stalls": scheduler_stalls,
                    "peak_scheduler_lag_ms": round(peak_lag_ms, 1),
                    "dropped_packets": relay.audio_dropped_packets - dropped_before,
                    "devices_at_start": initial_devices,
                    "devices_at_end": len(relay.clients),
                }
                if (elapsed > expected * 1.25 + 1 or
                        relay.audio_dropped_packets > dropped_before or
                        not relay.clients):
                    logging.getLogger('bee.ptt').warning(
                        'PTT audio degradation: %s', relay.last_audio_playback)
        finally:
            wf.close()
            if timer_requested:
                try:
                    ctypes.windll.winmm.timeEndPeriod(1)
                except Exception:
                    pass

        return {
            "ok": True,
            "packets": seq,
            "connected_devices": len(relay.clients),
            "device_name": device_name,
            "server_playback": True,
        }

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
            client.sender_task = asyncio.create_task(_writer(relay, client))
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
                except Exception:
                    # Expected when a mobile/watch connection disappears abruptly.
                    pass
    return relay

