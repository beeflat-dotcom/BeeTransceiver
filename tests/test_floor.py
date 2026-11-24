# -*- coding: utf-8 -*-
"""Relay packet compatibility and ordering regression tests."""
import asyncio
import pathlib
import struct
import sys
import unittest
import uuid

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "windows"))
from ptt_relay_api import _Relay, _Client, _valid_packet


def packet(label: str) -> bytes:
    name = label.encode("utf-8")
    pcm = b"\x00" * 640
    return (b"BPT1\x01" + uuid.uuid4().bytes + struct.pack(">I", 1)
            + bytes([len(name)]) + name + struct.pack(">H", len(pcm)) + pcm)


class RelayTests(unittest.IsolatedAsyncioTestCase):
    async def test_packet_format_remains_compatible(self):
        self.assertTrue(_valid_packet(packet("今店")))
        self.assertFalse(_valid_packet(b"invalid"))

    async def test_relay_queues_existing_binary_protocol_without_echo(self):
        relay = _Relay()
        a, b = _Client(None), _Client(None)
        await relay.join(a)
        await relay.join(b)
        try:
            data = packet("今店")
            await relay.broadcast(a, data)
            relay.ordered_voice.end(a)
            await asyncio.sleep(.1)
            self.assertTrue(b.queue.qsize() > 0)
            self.assertEqual(a.queue.qsize(), 0)
            self.assertEqual(b.queue.get_nowait(), data)
        finally:
            await relay.leave(a)
            await relay.leave(b)

    async def test_voice_turns_are_sequential_even_when_overlapping(self):
        relay = _Relay()
        a, b, listener = _Client(None), _Client(None), _Client(None)
        for c in (a, b, listener):
            await relay.join(c)
        try:
            data_a, data_b = packet("A"), packet("B")
            relay.ordered_voice.start(a)
            relay.ordered_voice.start(b)
            await relay.broadcast(a, data_a)
            await relay.broadcast(b, data_b)
            relay.ordered_voice.end(a)
            relay.ordered_voice.end(b)
            await asyncio.sleep(.7)
            received = []
            while not listener.queue.empty():
                received.append(listener.queue.get_nowait())
            self.assertEqual(received, [data_a, data_b])
        finally:
            for c in (a, b, listener):
                await relay.leave(c)


if __name__ == "__main__":
    unittest.main()
