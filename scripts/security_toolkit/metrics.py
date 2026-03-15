import asyncio
import collections
import statistics
import time
from dataclasses import dataclass
from typing import Deque, Dict, Optional

from .config import MetricsConfig

try:
    import psutil
except Exception:
    psutil = None


@dataclass
class NodeStats:
    cpu_percent: Optional[float]
    rss_mb: Optional[float]


class MetricsCollector:
    def __init__(self, config: MetricsConfig):
        self.config = config
        self.start_time = time.time()
        self.accepted = 0
        self.rejected = 0
        self.errors = 0
        self.sent = 0
        self.open_connections = 0
        self.closed_connections = 0
        self.signature_verify_ms: Deque[float] = collections.deque(maxlen=config.latency_window_size)
        self.latencies_ms: Deque[float] = collections.deque(maxlen=config.latency_window_size)
        self._report_task: Optional[asyncio.Task] = None
        self._lock = asyncio.Lock()

    async def start_reporting(self) -> None:
        if self._report_task is not None:
            return
        self._report_task = asyncio.create_task(self._report_loop())

    async def stop_reporting(self) -> None:
        if self._report_task is None:
            return
        self._report_task.cancel()
        try:
            await self._report_task
        except asyncio.CancelledError:
            pass
        self._report_task = None

    async def record_send(self, latency_ms: float, accepted: bool) -> None:
        async with self._lock:
            self.sent += 1
            if accepted:
                self.accepted += 1
            else:
                self.rejected += 1
            self.latencies_ms.append(latency_ms)

    async def record_signature_verify_ms(self, elapsed_ms: float) -> None:
        async with self._lock:
            self.signature_verify_ms.append(elapsed_ms)

    async def record_error(self) -> None:
        async with self._lock:
            self.errors += 1

    async def connection_opened(self) -> None:
        async with self._lock:
            self.open_connections += 1

    async def connection_closed(self) -> None:
        async with self._lock:
            self.open_connections = max(0, self.open_connections - 1)
            self.closed_connections += 1

    def snapshot(self) -> Dict:
        elapsed = max(time.time() - self.start_time, 1e-6)
        latencies = list(self.latencies_ms)
        verify_lat = list(self.signature_verify_ms)
        total = self.accepted + self.rejected
        rejection_rate = (self.rejected / total) * 100 if total else 0.0

        return {
            "uptime_sec": round(elapsed, 2),
            "tps": round(self.sent / elapsed, 2),
            "sent": self.sent,
            "accepted": self.accepted,
            "rejected": self.rejected,
            "errors": self.errors,
            "rejection_rate_pct": round(rejection_rate, 2),
            "latency_avg_ms": round(statistics.fmean(latencies), 2) if latencies else 0.0,
            "latency_p95_ms": round(_percentile(latencies, 95), 2) if latencies else 0.0,
            "latency_p99_ms": round(_percentile(latencies, 99), 2) if latencies else 0.0,
            "sig_verify_avg_ms": round(statistics.fmean(verify_lat), 4) if verify_lat else 0.0,
            "open_connections": self.open_connections,
            "closed_connections": self.closed_connections,
            "node_stats": _node_stats(self.config.node_pid),
        }

    async def _report_loop(self) -> None:
        while True:
            await asyncio.sleep(self.config.report_interval_sec)
            snap = self.snapshot()
            node_stats = snap["node_stats"]
            cpu = "n/a" if node_stats is None or node_stats.cpu_percent is None else f"{node_stats.cpu_percent:.1f}%"
            rss = "n/a" if node_stats is None or node_stats.rss_mb is None else f"{node_stats.rss_mb:.1f}MB"
            print(
                "[metrics] "
                f"tps={snap['tps']} sent={snap['sent']} accepted={snap['accepted']} rejected={snap['rejected']} "
                f"rej%={snap['rejection_rate_pct']} lat(avg/p95/p99)={snap['latency_avg_ms']}/"
                f"{snap['latency_p95_ms']}/{snap['latency_p99_ms']}ms sig={snap['sig_verify_avg_ms']}ms "
                f"conn(open/closed)={snap['open_connections']}/{snap['closed_connections']} node(cpu/rss)={cpu}/{rss}"
            )


def _percentile(values, percentile_value: int) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = int(round((percentile_value / 100) * (len(ordered) - 1)))
    return ordered[max(0, min(index, len(ordered) - 1))]


def _node_stats(pid: Optional[int]) -> Optional[NodeStats]:
    if pid is None or psutil is None:
        return None

    try:
        process = psutil.Process(pid)
        with process.oneshot():
            cpu = process.cpu_percent(interval=None)
            rss = process.memory_info().rss / (1024 * 1024)
        return NodeStats(cpu_percent=cpu, rss_mb=rss)
    except Exception:
        return NodeStats(cpu_percent=None, rss_mb=None)
