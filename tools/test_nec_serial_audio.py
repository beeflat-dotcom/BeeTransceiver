"""Standalone regression for NEC's mixed AI + human PTT speaker arbitration."""
import asyncio
import io
import json
import struct
import sys
import time
import uuid
import wave
from pathlib import Path

from fastapi import FastAPI

sys.path.insert(0, str(Path(__file__).resolve().parent))
from ptt_relay_api import install_ptt_relay_api  # noqa: E402

TOKEN = "test-token-not-production"

class FakeWebSocket:
    def __init__(self):
        self.headers = {"authorization": f"Bearer {TOKEN}"}
        self.input = asyncio.Queue()
        self.control = []
        self.audio = []
        self.accepted = False

    async def accept(self):
        self.accepted = True

    async def receive(self):
        return await self.input.get()

    async def send_text(self, value):
        self.control.append((time.monotonic(), json.loads(value)))

    async def send_bytes(self, value):
        self.audio.append((time.monotonic(), bytes(value)))

    async def close(self, code=1000):
        pass

    async def text(self, value):
        await self.input.put({"type": "websocket.receive", "text": json.dumps(value)})

    async def packet(self, sender_name):
        nb = sender_name.encode("utf-8")
        packet = (b"BPT1\x01" + uuid.uuid4().bytes +
                  struct.pack(">I", 1) + bytes([len(nb)]) + nb +
                  struct.pack(">H", 640) + (b"\x01\x02" * 320))
        await self.input.put({"type": "websocket.receive", "bytes": packet})

    async def disconnect(self):
        await self.input.put({"type": "websocket.disconnect"})

    def seen(self, msg_type):
        return [obj for _, obj in self.control if obj.get("type") == msg_type]

async def until(cond, timeout=3):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if cond():
            return
        await asyncio.sleep(0.015)
    raise AssertionError(f"Condition did not become true within {timeout}s")

class FakeRequest:
    def __init__(self, audio_seconds=0.32):
        self.headers = {"authorization": f"Bearer {TOKEN}"}
        self.query_params = {"device_name": "びーAI", "pre_ms": "0", "post_ms": "0"}
        # Generate non-silent 16-bit mono PCM WAV at the expected frequency.
        out = io.BytesIO()
        with wave.open(out, "wb") as wf:
            wf.setnchannels(1)
            wf.setsampwidth(2)
            wf.setframerate(16000)
            frames = int(16000 * audio_seconds)
            wf.writeframes(b"\x10\x27" * frames)
        self.wav = out.getvalue()

    async def body(self):
        return self.wav

async def main():
    app = FastAPI()
    relay = install_ptt_relay_api(app, lambda: TOKEN)
    ws_endpoint = next(r.endpoint for r in app.routes if getattr(r, "path", "") == "/ptt/ws")
    wav_endpoint = next(r.endpoint for r in app.routes if getattr(r, "path", "") == "/ptt/play-wav")
    health_endpoint = next(r.endpoint for r in app.routes if getattr(r, "path", "") == "/ptt/health")

    devices = [FakeWebSocket() for _ in range(3)]
    tasks = [asyncio.create_task(ws_endpoint(ws)) for ws in devices]
    a, b, legacy = devices
    await until(lambda: all(x.accepted for x in devices))
    await a.text({"type": "hello", "protocol": 2, "name": "今店1"})
    await b.text({"type": "hello", "protocol": 2, "name": "福成2"})
    await until(lambda: len(a.seen("hello_ack")) and len(b.seen("hello_ack")))
    assert a.seen("hello_ack")[-1]["floor_control"]
    await until(lambda: any(msg["count"] == 3 for msg in a.seen("ptt_peers")))
    assert (await health_endpoint())["floor_control"] is True

    # Human is already speaking: queued AI WAV cannot reach clients until release.
    await a.text({"type": "ptt_request", "name": "今店1"})
    await until(lambda: bool(a.seen("ptt_granted")))
    await a.packet("今店1")
    await until(lambda: len(b.audio) >= 1)
    before = len(b.audio)
    waiting_ai = asyncio.create_task(wav_endpoint(FakeRequest(0.30)))
    await asyncio.sleep(0.10)
    assert len(b.audio) == before, "AI packet overlapped human floor"
    assert (await health_endpoint())["floor_type"] == "human"
    await a.text({"type": "ptt_release"})
    await asyncio.wait_for(waiting_ai, timeout=4)
    assert len(b.audio) > before
    assert relay.turn_kind == "", "AI floor not released"
    print("PASS: human-first AI queue, audio separation, release")

    # AI is speaking: modern Android request waits for a single grant.
    b.control.clear()
    playing_ai = asyncio.create_task(wav_endpoint(FakeRequest(0.52)))
    await until(lambda: relay.turn_kind == "ai")
    await b.text({"type": "ptt_request", "name": "福成2"})
    await asyncio.sleep(0.10)
    assert not b.seen("ptt_granted"), "Human granted during AI playback"
    await asyncio.wait_for(playing_ai, timeout=4)
    await until(lambda: len(b.seen("ptt_granted")) == 1, timeout=3)
    assert relay.turn_kind == "human"
    await b.text({"type": "ptt_release"})
    await until(lambda: relay.turn_kind == "", timeout=3)
    print("PASS: AI-first human grant, serialized state")

    # Older phones have no grant handshake. Buffer stream until current turn ends.
    starting = len(b.audio)
    playing_ai = asyncio.create_task(wav_endpoint(FakeRequest(0.52)))
    await until(lambda: relay.turn_kind == "ai")
    await legacy.packet("旧端末3")
    await asyncio.sleep(0.08)
    assert not any(p[-640:] == b"\x01\x02" * 320 for _, p in b.audio[starting:]), \
        "Legacy voice reached client while AI was speaking"
    await asyncio.wait_for(playing_ai, timeout=4)
    await until(lambda: any(p[-640:] == b"\x01\x02" * 320 for _, p in b.audio[starting:]),
                timeout=3)
    await until(lambda: relay.turn_kind == "", timeout=3)
    print("PASS: legacy stream buffering, no parallel packets")

    # A long AI utterance must yield to pending humans before finishing.
    b.control.clear()
    long_ai = asyncio.create_task(wav_endpoint(FakeRequest(6.2)))
    await until(lambda: relay.turn_kind == "ai")
    t0 = time.monotonic()
    await b.text({"type": "ptt_request", "name": "福成2"})
    await until(lambda: bool(b.seen("ptt_granted")), timeout=5.6)
    assert time.monotonic() - t0 < 5.4
    assert not long_ai.done(), "AI did not yield; human was granted after full WAV"
    await b.text({"type": "ptt_release"})
    await asyncio.wait_for(long_ai, timeout=12)
    assert relay.last_audio_playback.get("yielded_to_humans", 0) > 0
    await until(lambda: relay.turn_kind == "", timeout=3)
    print("PASS: long AI yields within 4s then resumes without lost frames")

    assert (await health_endpoint())["connected_devices"] == 3
    for ws in devices:
        await ws.disconnect()
    await asyncio.wait_for(asyncio.gather(*tasks), 3)
    assert not relay.clients
    print("ALL_SERVER_SERIAL_AUDIO_TESTS_PASSED")

asyncio.run(main())
