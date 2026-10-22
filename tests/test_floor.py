# -*- coding: utf-8 -*-
"""Offline regression tests for server-enforced single-speaker PTT."""
import asyncio
import pathlib
import struct
import sys
import unittest
import uuid

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "windows"))
from ptt_relay_api import _Relay, _Client, _valid_packet, _clean_name


def packet(name: str) -> bytes:
    label = name.encode("utf-8")
    pcm = b"\x00" * 640
    return b"BPT1\x01" + uuid.uuid4().bytes + struct.pack(">I", 1) + bytes([len(label)]) + label + struct.pack(">H", len(pcm)) + pcm


def events(client):
    values = []
    while not client.queue.empty():
        values.append(client.queue.get_nowait())
    return values


class TestFloor(unittest.IsolatedAsyncioTestCase):
    async def test_only_one_sender(self):
        relay = _Relay()
        alpha, beta, listener = _Client(None), _Client(None), _Client(None)
        for client in (alpha, beta, listener):
            self.assertTrue(await relay.join(client))
            events(client)
        await relay.request(alpha, "今店・天野")
        await relay.request(beta, "福成・八木")
        self.assertIs(relay.holder, alpha)
        self.assertEqual(relay.holder_name, "今店・天野")
        beta_events = events(beta)
        self.assertTrue(any(b'ptt_busy' in x[1].encode() for x in beta_events if x[0] == "text"))
        events(listener)

        await relay.receive_audio(beta, packet("福成"))
        self.assertFalse(any(kind == "bytes" for kind, _ in events(listener)))
        await relay.receive_audio(alpha, packet("今店"))
        self.assertTrue(any(kind == "bytes" for kind, _ in events(listener)))
        await relay.release(alpha)
        await relay.request(beta, "福成・八木")
        self.assertIs(relay.holder, beta)

    async def test_legacy_packet_claim_and_expires(self):
        relay = _Relay()
        legacy, listener = _Client(None), _Client(None)
        await relay.join(legacy)
        await relay.join(listener)
        events(legacy);events(listener)
        await relay.receive_audio(legacy, packet("旧版1"))
        self.assertIs(relay.holder, legacy)
        self.assertEqual(relay.holder_name, "旧版1")
        self.assertTrue(any(kind == "bytes" for kind, _ in events(listener)))
        relay.last_audio -= 10.0
        self.assertTrue(await relay.expire())
        self.assertIsNone(relay.holder)

    async def test_disconnect_frees_floor(self):
        relay = _Relay()
        a, b = _Client(None), _Client(None)
        await relay.join(a);await relay.join(b)
        await relay.request(a, "今")
        await relay.leave(a)
        self.assertIsNone(relay.holder)
        await relay.request(b, "福")
        self.assertIs(relay.holder, b)

    async def test_control_and_packet_validation(self):
        self.assertTrue(_valid_packet(packet("テスト")))
        self.assertFalse(_valid_packet(b"invalid"))
        name = _clean_name("い" * 25)
        self.assertLessEqual(len(name.encode("utf-8")), 48)
        relay = _Relay()
        client = _Client(None)
        await relay.join(client)
        await relay.request(client, "発言者")
        self.assertTrue(any("ptt_granted" in data for kind, data in events(client) if kind == "text"))


if __name__ == "__main__":
    unittest.main()
