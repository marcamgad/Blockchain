import asyncio
from typing import List

from .chaos import ChaosProfile
from .client import DeviceClient
from .config import ScenarioConfig, ToolkitConfig
from .crypto_utils import IoTIdentity
from .metrics import MetricsCollector
from .safeguards import validate_safeguards


class ToolkitOrchestrator:
    def __init__(self, config: ToolkitConfig):
        self.config = config
        self.metrics = MetricsCollector(config.metrics)

    async def run(self) -> None:
        validate_safeguards(self.config)
        await self.metrics.start_reporting()
        try:
            for scenario in self.config.scenarios:
                if not scenario.enabled:
                    continue
                print(f"[scenario] start name={scenario.name} duration={scenario.duration_sec}s intensity={scenario.intensity}")
                await self._run_scenario(scenario)
                print(f"[scenario] end name={scenario.name}")
        finally:
            await self.metrics.stop_reporting()
            print(f"[summary] {self.metrics.snapshot()}")

    async def _run_scenario(self, scenario: ScenarioConfig) -> None:
        stop_event = asyncio.Event()
        chaos = _chaos_for_scenario(scenario.name, scenario.intensity)

        clients: List[DeviceClient] = []
        tasks: List[asyncio.Task] = []

        for i in range(self.config.devices):
            identity = IoTIdentity.create(device_id=f"virtual-iot-{i}")
            client = DeviceClient(
                identity=identity,
                node=self.config.node,
                profile=self.config.profile,
                metrics=self.metrics,
                scenario_name=scenario.name,
                scenario_intensity=scenario.intensity,
                chaos=chaos,
                global_tps=self.config.tps_target,
                device_count=self.config.devices,
                stop_event=stop_event,
            )
            clients.append(client)

        for client in clients:
            tasks.append(asyncio.create_task(client.run()))

        try:
            await asyncio.sleep(max(1, scenario.duration_sec))
        finally:
            stop_event.set()
            await asyncio.gather(*tasks, return_exceptions=True)


def _chaos_for_scenario(name: str, intensity: float) -> ChaosProfile:
    if name == "chaos_latency":
        return ChaosProfile(enabled=True, min_latency_ms=10, max_latency_ms=int(250 * max(1.0, intensity)))
    if name == "chaos_packet_loss":
        return ChaosProfile(enabled=True, drop_probability=min(0.8, 0.05 * max(1.0, intensity)))
    if name == "chaos_partition":
        return ChaosProfile(enabled=True, partition_probability=min(0.9, 0.1 * max(1.0, intensity)))
    if name == "validator_isolation":
        return ChaosProfile(enabled=True, partition_probability=min(0.95, 0.2 * max(1.0, intensity)))
    return ChaosProfile(enabled=False)
