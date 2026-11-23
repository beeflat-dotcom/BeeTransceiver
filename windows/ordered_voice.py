# -*- coding: utf-8 -*-
"""Ordered, per-recipient, low-latency PTT turn playback.

Each transmitter records independently. An utterance is immediately available to
receivers when it is first in their playback queue; simultaneous utterances wait
without being mixed. Own voice never enters the recipient's playback queue.
No recordings are persisted by this component.
"""
from __future__ import annotations

import asyncio
import logging
import time
from collections import deque
from dataclasses import dataclass, field
from typing import Any

PACKET_SECONDS = 0.020
TURN_GAP_SECONDS = 0.50
IDLE_END_SECONDS = 0.65
MAX_TURN_SECONDS = 20.0
MAX_FRAMES_PER_TURN = 1050
MAX_FRAMES_PER_SYSTEM_TURN = 16000
MAX_PENDING_TURNS = 12
MAX_WAIT_SECONDS = 180.0

log = logging.getLogger("bee.ptt.ordered")


@dataclass(eq=False)
class Turn:
    owner: Any
    started: float
    frames: list[bytes] = field(default_factory=list)
    finished: bool = False
    is_system_voice: bool = False
    last_frame: float = field(default_factory=time.monotonic)
    signal: asyncio.Event = field(default_factory=asyncio.Event)

    def append(self, packet: bytes) -> None:
        if not self.finished:
            if len(self.frames) >= (MAX_FRAMES_PER_SYSTEM_TURN if self.is_system_voice else MAX_FRAMES_PER_TURN):
                self.finish()
                return
            self.frames.append(packet)
            self.last_frame = time.monotonic()
            self.signal.set()

    def finish(self) -> None:
        self.finished = True
        self.signal.set()


class OrderedVoice:
    def __init__(self) -> None:
        self.clients: set[Any] = set()
        self.pending: dict[Any, deque[Turn]] = {}
        self.active: dict[Any, Turn] = {}
        self.tasks: dict[Any, asyncio.Task] = {}
        self.dropped_turns = 0

    def join(self, client: Any) -> None:
        self.clients.add(client)
        self.pending[client] = deque()
        self.tasks[client] = asyncio.create_task(self._play(client))

    async def leave(self, client: Any) -> None:
        self.clients.discard(client)
        turn = self.active.pop(client, None)
        if turn is not None:
            turn.finish()
        task = self.tasks.pop(client, None)
        if task:
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                pass
        self.pending.pop(client, None)

    def start(self, client: Any) -> None:
        # New utterances are separated even when the same device speaks again.
        previous = self.active.pop(client, None)
        if previous is not None:
            previous.finish()
        turn = Turn(client, time.monotonic(), is_system_voice=client not in self.clients)
        self.active[client] = turn
        for peer in self.clients:
            if peer is client:
                continue
            q = self.pending.get(peer)
            if q is None:
                continue
            if len(q) >= MAX_PENDING_TURNS:
                self.dropped_turns += 1
                log.warning("PTT queue full: dropped entire turn for a receiver")
                continue
            q.append(turn)

    def audio(self, client: Any, packet: bytes) -> None:
        turn = self.active.get(client)
        if turn is None or turn.finished:
            self.start(client)
            turn = self.active[client]
        if not turn.is_system_voice and time.monotonic() - turn.started >= MAX_TURN_SECONDS:
            turn.finish()
            return
        turn.append(bytes(packet))

    def end(self, client: Any) -> None:
        turn = self.active.pop(client, None)
        if turn:
            turn.finish()

    def tick(self) -> None:
        now = time.monotonic()
        for client, turn in list(self.active.items()):
            if ((not turn.is_system_voice and now - turn.started >= MAX_TURN_SECONDS)
                    or now - turn.last_frame >= IDLE_END_SECONDS):
                turn.finish()
                self.active.pop(client, None)

    def queued(self) -> int:
        return sum(len(q) for q in self.pending.values())

    async def _play(self, receiver: Any) -> None:
        try:
            while receiver in self.clients:
                q = self.pending.get(receiver)
                if q is None:
                    return
                if not q:
                    await asyncio.sleep(0.010)
                    continue
                turn = q[0]
                if time.monotonic() - turn.started > MAX_WAIT_SECONDS:
                    q.popleft()
                    self.dropped_turns += 1
                    log.warning("PTT stale turn expired")
                    continue
                index = 0
                next_at = time.monotonic()
                while receiver in self.clients:
                    if receiver in self.active:
                        # Do not replay other voices while this device records.
                        next_at = time.monotonic()
                        await asyncio.sleep(0.020)
                        continue
                    if index >= len(turn.frames):
                        if turn.finished:
                            break
                        turn.signal.clear()
                        try:
                            await asyncio.wait_for(turn.signal.wait(), timeout=0.12)
                        except asyncio.TimeoutError:
                            continue
                        continue
                    packet = turn.frames[index]
                    index += 1
                    delay = next_at - time.monotonic()
                    if delay > 0:
                        await asyncio.sleep(delay)
                    else:
                        next_at = time.monotonic()
                    # Bounded websocket buffer: slow network cannot grow forever.
                    await receiver.send_ordered(packet)
                    next_at += PACKET_SECONDS
                if q and q[0] is turn:
                    q.popleft()
                if q:
                    await asyncio.sleep(TURN_GAP_SECONDS)
        except asyncio.CancelledError:
            raise
        except Exception:
            log.exception("PTT ordered playback failed")
