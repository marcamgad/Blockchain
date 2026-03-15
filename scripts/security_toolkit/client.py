import asyncio
import base64
import hashlib
import json
import ssl
import time
import random
from typing import Optional

import ecdsa

from .chaos import ChaosProfile
from .config import IoTProfile, NodeConfig
from .crypto_utils import IoTIdentity
from .metrics import MetricsCollector
from .scenarios import ScenarioState, apply_scenario


class DeviceClient:
    def __init__(
        self,
        identity: IoTIdentity,
        node: NodeConfig,
        profile: IoTProfile,
        metrics: MetricsCollector,
        scenario_name: str,
        scenario_intensity: float,
        chaos: ChaosProfile,
        global_tps: int,
        device_count: int,
        stop_event: asyncio.Event,
    ):
        self.identity = identity
        self.node = node
        self.profile = profile
        self.metrics = metrics
        self.scenario_name = scenario_name
        self.scenario_intensity = scenario_intensity
        self.chaos = chaos
        self.global_tps = global_tps
        self.device_count = device_count
        self.stop_event = stop_event
        self._scenario_state = ScenarioState(replay_cache=[])
        self._writer: Optional[asyncio.StreamWriter] = None

    async def run(self) -> None:
        while not self.stop_event.is_set():
            if self._writer is None:
                await self._connect()
                if self._writer is None:
                    await asyncio.sleep(0.2)
                    continue

            if self.chaos.should_partition():
                await asyncio.sleep(0.5)
                continue

            if random.random() < self.profile.disconnect_chance:
                await self._disconnect()
                await asyncio.sleep(self.profile.reconnect_delay_ms / 1000)
                continue

            if self.identity.nonce > 0 and self.identity.nonce % self.profile.identity_rotate_every == 0:
                self.identity.rotate()

            burst = 1
            if random.random() < self.profile.burst_chance:
                burst = random.randint(self.profile.burst_size_min, self.profile.burst_size_max)

            for _ in range(burst):
                await self._send_once()

            await asyncio.sleep(self._device_delay_sec())

        await self._disconnect()

    async def _connect(self) -> None:
        context = None
        if self.node.use_tls:
            context = ssl.create_default_context()
            if self.node.tls_insecure:
                context.check_hostname = False
                context.verify_mode = ssl.CERT_NONE

        try:
            _, writer = await asyncio.open_connection(self.node.host, self.node.port, ssl=context)
            self._writer = writer
            await self.metrics.connection_opened()
        except Exception:
            self._writer = None
            await self.metrics.record_error()

    async def _disconnect(self) -> None:
        if self._writer is None:
            return
        try:
            self._writer.close()
            await self._writer.wait_closed()
        except Exception:
            pass
        finally:
            self._writer = None
            await self.metrics.connection_closed()

    def _device_delay_sec(self) -> float:
        base = max(1, self.device_count) / max(1, self.global_tps)
        min_jitter = self.profile.telemetry_min_interval_ms / 1000.0
        max_jitter = self.profile.telemetry_max_interval_ms / 1000.0
        jitter = random.uniform(min_jitter, max_jitter)
        return max(0.0, min(base, jitter))

    async def _send_once(self) -> None:
        if self._writer is None:
            return

        if self.chaos.should_drop():
            await self.metrics.record_send(latency_ms=0.0, accepted=False)
            return

        tx = self.identity.create_transaction(
            to_did="did:hybrid:receiver",
            amount=1,
            data=f"TELEMETRY:{random.uniform(18.0, 42.0):.2f}C",
        )
        payload_bytes = json.dumps(tx, separators=(",", ":")).encode()
        verify_start = time.perf_counter()
        try:
            self.identity.verifying_key.verify(
                bytes.fromhex(tx["signature"]),
                json.dumps({k: tx[k] for k in tx if k not in {"signature", "pubKey"}}, sort_keys=True, separators=(",", ":")).encode(),
                hashfunc=hashlib.sha256,
            )
            verify_elapsed = (time.perf_counter() - verify_start) * 1000
            await self.metrics.record_signature_verify_ms(verify_elapsed)
        except ecdsa.BadSignatureError:
            await self.metrics.record_signature_verify_ms((time.perf_counter() - verify_start) * 1000)

        p2p_message = {
            "senderId": self.identity.did,
            "type": "TRANSACTION",
            "payload": base64.b64encode(payload_bytes).decode(),
            "signature": base64.b64encode(self.identity.sign(payload_bytes)).decode(),
            "timestamp": int(time.time() * 1000),
        }

        message_to_send = apply_scenario(
            self.scenario_name,
            p2p_message,
            tx,
            self._scenario_state,
            self.scenario_intensity,
        )

        if message_to_send is None:
            await self.metrics.record_send(latency_ms=0.0, accepted=False)
            return

        extra_delay_ms = self.chaos.latency_ms()
        if extra_delay_ms > 0:
            await asyncio.sleep(extra_delay_ms / 1000)

        encoded = json.dumps(message_to_send, separators=(",", ":")).encode()
        started = time.perf_counter()
        try:
            self._writer.write(len(encoded).to_bytes(4, byteorder="big"))
            self._writer.write(encoded)
            await self._writer.drain()
            elapsed_ms = (time.perf_counter() - started) * 1000
            await self.metrics.record_send(latency_ms=elapsed_ms, accepted=True)
        except Exception:
            elapsed_ms = (time.perf_counter() - started) * 1000
            await self.metrics.record_send(latency_ms=elapsed_ms, accepted=False)
            await self.metrics.record_error()
            await self._disconnect()
