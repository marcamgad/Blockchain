from dataclasses import dataclass, field
from typing import List, Optional


@dataclass
class SafeguardConfig:
    local_only: bool = True
    allow_private_subnets: bool = True
    max_devices: int = 100_000
    max_connections_per_host: int = 20_000


@dataclass
class NodeConfig:
    host: str = "127.0.0.1"
    port: int = 6001
    use_tls: bool = True
    tls_insecure: bool = True


@dataclass
class IoTProfile:
    telemetry_min_interval_ms: int = 500
    telemetry_max_interval_ms: int = 3_000
    burst_chance: float = 0.08
    burst_size_min: int = 3
    burst_size_max: int = 15
    disconnect_chance: float = 0.02
    reconnect_delay_ms: int = 750
    identity_rotate_every: int = 500


@dataclass
class ScenarioConfig:
    name: str = "baseline"
    enabled: bool = True
    intensity: float = 1.0
    duration_sec: int = 60


@dataclass
class MetricsConfig:
    report_interval_sec: int = 3
    latency_window_size: int = 50_000
    node_pid: Optional[int] = None


@dataclass
class ToolkitConfig:
    node: NodeConfig = field(default_factory=NodeConfig)
    safeguards: SafeguardConfig = field(default_factory=SafeguardConfig)
    profile: IoTProfile = field(default_factory=IoTProfile)
    metrics: MetricsConfig = field(default_factory=MetricsConfig)
    devices: int = 1000
    tps_target: int = 100
    scenarios: List[ScenarioConfig] = field(default_factory=lambda: [ScenarioConfig(name="baseline")])
