import random
from dataclasses import dataclass
from typing import Set


@dataclass
class ChaosProfile:
    enabled: bool = False
    min_latency_ms: int = 0
    max_latency_ms: int = 0
    drop_probability: float = 0.0
    partition_probability: float = 0.0
    isolated_validators: Set[str] = None

    def __post_init__(self):
        if self.isolated_validators is None:
            self.isolated_validators = set()

    def should_drop(self) -> bool:
        if not self.enabled:
            return False
        return random.random() < self.drop_probability

    def should_partition(self) -> bool:
        if not self.enabled:
            return False
        return random.random() < self.partition_probability

    def latency_ms(self) -> int:
        if not self.enabled or self.max_latency_ms <= 0:
            return 0
        low = max(0, self.min_latency_ms)
        high = max(low, self.max_latency_ms)
        return random.randint(low, high)
