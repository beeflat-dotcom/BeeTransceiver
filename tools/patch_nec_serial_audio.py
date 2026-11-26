#!/usr/bin/env python3
"""Patch NEC's live PTT server source in a STAGING COPY ONLY.

Adds FIFO server-side speaker arbitration: managed Android handshakes,
AI WAV playback, and buffered legacy packet sessions share one floor lock.
The public audio endpoint, pairing, and package identity remain unchanged.
"""
from pathlib import Path
import sys

path = Path(sys.argv[1])
s = path.read_text(encoding="utf-8")

def patch(old, new, label):
    global s
    count=s.count(old)
    if count != 1:
        raise AssertionError(f"{label}: expected exactly one marker, got {count}")
    s=s.replace(old,new,1)

patch('PTT_RELAY_VERSION = "2026-10-29-ptt-ws-05-realtime-pacing"',
      'PTT_RELAY_VERSION = "2026-11-26-ptt-all-audio-serialized-01"',
      'version')
patch('SERVER_SENDER_ID = uuid.uuid5(uuid.NAMESPACE_DNS, "jp.beeflat.bee-ptt-server-playback")',
      '''SERVER_SENDER_ID = uuid.uuid5(uuid.NAMESPACE_DNS, "jp.beeflat.bee-ptt-server-playback")
TURN_GAP_SECONDS = 0.36
MAX_HUMAN_TURN_SECONDS = 23.0
MAX_LEGACY_TURN_SECONDS = 23.0
LEGACY_IDLE_SECONDS = 0.65
AI_MAX_PREEMPT_WAIT_SECONDS = 4.0
''', 'constants')
patch('    queue: asyncio.Queue[bytes] = field(default_factory=lambda: asyncio.Queue(maxsize=96))',
      '    queue: asyncio.Queue[bytes | str] = field(default_factory=lambda: asyncio.Queue(maxsize=96))',
      'peer outgoing queue')
patch('    label_id: str = field(default_factory=lambda: uuid.uuid4().hex[:6])',
      '''    label_id: str = field(default_factory=lambda: uuid.uuid4().hex[:6])
    managed: bool = False
    human_task: asyncio.Task | None = None
    human_done: asyncio.Event = field(default_factory=asyncio.Event)
    legacy_queue: asyncio.Queue[bytes] = field(
        default_factory=lambda: asyncio.Queue(maxsize=1250))
    legacy_task: asyncio.Task | None = None
''','peer arbitration fields')
patch('        self.last_audio_playback = {}\n\n    async def join',
'''        self.last_audio_playback = {}
        # One floor for AI, managed Android and older voice devices.
        self.turn_lock = asyncio.Lock()
        self.turn_owner: _Client | None = None
        self.turn_name = ""
        self.turn_kind = ""
        self.waiting_humans = 0
        self.legacy_dropped_packets = 0

    async def announce_floor(self, targets: list[_Client] | None = None) -> None:
        if targets is None:
            async with self.lock:
                targets = list(self.clients)
        for peer in targets:
            message = json.dumps({
                "type": "ptt_state",
                "name": self.turn_name,
                "active": bool(self.turn_name),
                "mine": self.turn_owner is peer and self.turn_kind == "human",
            }, ensure_ascii=False)
            await self._enqueue([peer], message)

    async def begin_turn(
        self, kind: str, name: str, owner: _Client | None
    ) -> None:
        # Must hold turn_lock before calling.
        self.turn_kind = kind
        self.turn_name = name or "端末"
        self.turn_owner = owner
        await self.announce_floor()

    async def end_turn(self) -> None:
        # Android's AudioTrack can have unplayed frames in its output buffer.
        self.turn_owner = None
        self.turn_name = ""
        self.turn_kind = ""
        await self.announce_floor()
        await asyncio.sleep(TURN_GAP_SECONDS)

    async def request_human(self, client: _Client, name: str) -> None:
        if client.human_task and not client.human_task.done():
            return
        client.human_done = asyncio.Event()
        client.human_task = asyncio.create_task(
            self._human_turn(client, _clean_client_name(name) or client.name or "端末")
        )

    async def _human_turn(self, client: _Client, name: str) -> None:
        queued = True
        self.waiting_humans += 1
        try:
            async with self.turn_lock:
                self.waiting_humans -= 1
                queued = False
                async with self.lock:
                    connected = client in self.clients
                if not connected or client.human_done.is_set():
                    return
                await self.begin_turn("human", name, client)
                try:
                    await self._enqueue([client], json.dumps(
                        {"type": "ptt_granted", "name": name}, ensure_ascii=False))
                    try:
                        await asyncio.wait_for(
                            client.human_done.wait(), timeout=MAX_HUMAN_TURN_SECONDS)
                    except asyncio.TimeoutError:
                        pass
                finally:
                    await self.end_turn()
        except asyncio.CancelledError:
            raise
        finally:
            if queued:
                self.waiting_humans -= 1

    async def release_human(self, client: _Client) -> None:
        client.human_done.set()
        task = client.human_task
        if task and not task.done() and self.turn_owner is not client:
            task.cancel()

    async def offer_legacy_packet(self, client: _Client, data: bytes) -> None:
        # Old versions have no permission handshake: collect their packet
        # stream in bounded memory and replay it as a separate FIFO turn.
        if client.legacy_queue.full():
            try:
                client.legacy_queue.get_nowait()
                self.legacy_dropped_packets += 1
            except asyncio.QueueEmpty:
                pass
        try:
            client.legacy_queue.put_nowait(data)
        except asyncio.QueueFull:
            self.legacy_dropped_packets += 1
        if not client.legacy_task or client.legacy_task.done():
            client.legacy_task = asyncio.create_task(self._legacy_turn(client))

    async def _legacy_turn(self, client: _Client) -> None:
        queued = True
        self.waiting_humans += 1
        try:
            async with self.turn_lock:
                self.waiting_humans -= 1
                queued = False
                async with self.lock:
                    if client not in self.clients:
                        return
                await self.begin_turn("legacy", client.name or "旧端末", client)
                try:
                    loop = asyncio.get_running_loop()
                    next_at = loop.time()
                    deadline = next_at + MAX_LEGACY_TURN_SECONDS
                    while loop.time() < deadline:
                        try:
                            packet = await asyncio.wait_for(
                                client.legacy_queue.get(), timeout=LEGACY_IDLE_SECONDS)
                        except asyncio.TimeoutError:
                            break
                        async with self.lock:
                            if client not in self.clients:
                                break
                        name = _packet_name(packet)
                        if name and name != client.name:
                            client.name = name
                            await self.announce_roster()
                        await self.broadcast(client, packet)
                        next_at = max(loop.time(), next_at) + PACKET_SECONDS
                        gap = next_at - loop.time()
                        if gap > 0:
                            await asyncio.sleep(gap)
                finally:
                    await self.end_turn()
        except asyncio.CancelledError:
            raise
        finally:
            if queued:
                self.waiting_humans -= 1
            if client.legacy_task is asyncio.current_task():
                client.legacy_task = None
            async with self.lock:
                connected = client in self.clients
            if connected and not client.legacy_queue.empty():
                client.legacy_task = asyncio.create_task(self._legacy_turn(client))

    async def join''','server floor implementation')
patch('''    async def leave(self, client: _Client) -> None:
        async with self.lock:
            self.clients.discard(client)
''',
'''    async def leave(self, client: _Client) -> None:
        async with self.lock:
            self.clients.discard(client)
        await self.release_human(client)
        if client.legacy_task and not client.legacy_task.done():
            client.legacy_task.cancel()
''','disconnect cleanup')
patch('''            "audio_dropped_packets": relay.audio_dropped_packets,
            "last_audio_playback": relay.last_audio_playback,''',
'''            "audio_dropped_packets": relay.audio_dropped_packets,
            "legacy_dropped_packets": relay.legacy_dropped_packets,
            "floor_type": relay.turn_kind or "idle",
            "floor_speaker": relay.turn_name,
            "floor_waiting": relay.waiting_humans,
            "floor_control": True,
            "last_audio_playback": relay.last_audio_playback,''', 'health')
# Modify the WAV playback block without changing any pacing and metrics.
start=s.index('            async with relay.playback_lock:\n')
end=s.index('        finally:\n            wf.close()',start)
block=s[start:end]
intro='            async with relay.playback_lock:\n'
assert block.startswith(intro)
inner=block[len(intro):]
needle='''                seq = 0

                async def emit(audio: bytes) -> None:
                    nonlocal next_at, seq, peak_lag_ms, scheduler_stalls
                    now = loop.time()
'''
assert inner.count(needle)==1,'WAV emit anchor'
replacement='''                seq = 0
                human_wait_started: float | None = None
                quiet_packets = 0
                yielded_to_humans = 0

                async def emit(audio: bytes) -> None:
                    nonlocal next_at, seq, peak_lag_ms, scheduler_stalls
                    nonlocal human_wait_started, quiet_packets, yielded_to_humans
                    nonlocal ai_has_turn
                    if relay.waiting_humans > 0:
                        current = loop.time()
                        if human_wait_started is None:
                            human_wait_started = current
                        quiet = len(audio) == PACKET_AUDIO_BYTES and max(
                            abs(sample) for sample in struct.unpack("<320h", audio)
                        ) < 500
                        quiet_packets = quiet_packets + 1 if quiet else 0
                        if (seq > 0 and (
                            quiet_packets >= 6 or
                            current-human_wait_started >= AI_MAX_PREEMPT_WAIT_SECONDS
                        )):
                            # Yield the AI voice to waiting humans. Resume from
                            # the very next PCM frame; no sentence is discarded.
                            await relay.end_turn()
                            relay.turn_lock.release()
                            ai_has_turn = False
                            await asyncio.sleep(0)
                            await relay.turn_lock.acquire()
                            ai_has_turn = True
                            await relay.begin_turn("ai", device_name, None)
                            next_at = loop.time()
                            yielded_to_humans += 1
                            human_wait_started = None
                            quiet_packets = 0
                    else:
                        human_wait_started = None
                        quiet_packets = 0
                    now = loop.time()
'''
inner=inner.replace(needle,replacement,1)
inner=inner.replace('''                    "devices_at_end": len(relay.clients),
''','''                    "devices_at_end": len(relay.clients),
                    "yielded_to_humans": yielded_to_humans,
''',1)
inner=inner.replace('''                if (elapsed > expected * 1.25 + 1 or
''','''                if ((yielded_to_humans == 0 and elapsed > expected * 1.25 + 1) or
''',1)
# Keep exception-safe lock ownership, including cancellation when AI yields.
wrapped = (intro +
'''                ai_has_turn = False
                try:
                    await relay.turn_lock.acquire()
                    ai_has_turn = True
                    await relay.begin_turn("ai", device_name, None)
''' +
 '\n'.join(('    '+line if line.strip() else line) for line in inner.rstrip('\n').split('\n')) + '\n' +
'''                finally:
                    if ai_has_turn:
                        try:
                            await relay.end_turn()
                        finally:
                            relay.turn_lock.release()
''')
s=s[:start]+wrapped+s[end:]
patch('''            client.sender_task = asyncio.create_task(_writer(relay, client))
            await relay.announce_roster()
''',
'''            client.sender_task = asyncio.create_task(_writer(relay, client))
            await relay.announce_roster()
            await relay.announce_floor([client])
''','initial state')
patch('''                    if isinstance(command, dict) and command.get("type") == "hello":
                        new_name = _clean_client_name(command.get("name", ""))
                        if new_name != client.name:
                            client.name = new_name
                            await relay.announce_roster()
                    continue''',
'''                    if isinstance(command, dict):
                        kind = command.get("type")
                        if kind == "hello":
                            new_name = _clean_client_name(command.get("name", ""))
                            if new_name and new_name != client.name:
                                client.name = new_name
                                await relay.announce_roster()
                            if command.get("protocol") == 2:
                                client.managed = True
                                await relay._enqueue([client], json.dumps({
                                    "type": "hello_ack", "protocol": 2,
                                    "floor_control": True}))
                                await relay.announce_floor([client])
                        elif kind == "ptt_request" and client.managed:
                            name = _clean_client_name(command.get("name", ""))
                            if name and name != client.name:
                                client.name = name
                                await relay.announce_roster()
                            await relay.request_human(client, name)
                        elif kind == "ptt_release" and client.managed:
                            await relay.release_human(client)
                    continue''','managed protocol')
patch('''                    await relay.broadcast(client, bytes(data))
''',
'''                    if client.managed:
                        if relay.turn_owner is client and relay.turn_kind == "human":
                            await relay.broadcast(client, bytes(data))
                    else:
                        await relay.offer_legacy_packet(client, bytes(data))
''','forwarding arbitration')
path.write_text(s,encoding='utf-8')
print('PATCH_OK',str(path))
