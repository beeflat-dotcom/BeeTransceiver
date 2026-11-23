# -*- coding: utf-8 -*-
"""Offline behavior checks for sequential PTT voice turns."""
import asyncio
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "windows"))
from ordered_voice import OrderedVoice


class Peer:
    def __init__(self, name):
        self.name = name
        self.received = []

    async def send_ordered(self, packet):
        self.received.append(packet)


class OrderedVoiceTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.voice = OrderedVoice()
        self.a, self.b, self.c = Peer("A"), Peer("B"), Peer("C")
        for p in (self.a, self.b, self.c):
            self.voice.join(p)

    async def asyncTearDown(self):
        for p in (self.a, self.b, self.c):
            await self.voice.leave(p)

    async def test_simultaneous_talk_sends_whole_turns_without_self_echo(self):
        v = self.voice
        v.start(self.a)
        v.start(self.b)
        for i in range(4):
            v.audio(self.a, b"A" + bytes([i]))
            v.audio(self.b, b"B" + bytes([i]))
        v.end(self.a)
        v.end(self.b)
        await asyncio.sleep(1.0)
        self.assertEqual(self.c.received, [b"A" + bytes([i]) for i in range(4)] +
                         [b"B" + bytes([i]) for i in range(4)])
        self.assertEqual(self.a.received, [b"B" + bytes([i]) for i in range(4)])
        self.assertEqual(self.b.received, [b"A" + bytes([i]) for i in range(4)])

    async def test_receiver_speaking_defers_other_audio(self):
        v = self.voice
        v.start(self.a)
        v.start(self.b)
        v.audio(self.a, b"hello")
        v.end(self.a)
        await asyncio.sleep(.10)
        self.assertEqual(self.b.received, [])
        v.end(self.b)
        await asyncio.sleep(.18)
        self.assertEqual(self.b.received, [b"hello"])

    async def test_idle_end_and_20_second_cap(self):
        v = self.voice
        v.start(self.a)
        turn = v.active[self.a]
        turn.last_frame -= 2.0
        v.tick()
        self.assertTrue(turn.finished)
        v.start(self.a)
        turn = v.active[self.a]
        turn.started -= 21.0
        v.tick()
        self.assertTrue(turn.finished)

    async def test_no_duplicate_echo_when_single_transmitter(self):
        v = self.voice
        v.start(self.a)
        v.audio(self.a, b"A1")
        v.end(self.a)
        await asyncio.sleep(.1)
        self.assertEqual(self.a.received, [])
        self.assertEqual(self.b.received, [b"A1"])
        self.assertEqual(self.c.received, [b"A1"])


if __name__ == "__main__":
    unittest.main()
