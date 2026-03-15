import argparse
import asyncio
import json
from pathlib import Path
from typing import List

from security_toolkit import ToolkitConfig, ToolkitOrchestrator
from security_toolkit.config import MetricsConfig, NodeConfig, ScenarioConfig


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Local IoT blockchain resilience testing toolkit")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=6001)
    parser.add_argument("--devices", type=int, default=1000)
    parser.add_argument("--tps", type=int, default=100)
    parser.add_argument("--duration", type=int, default=60)
    parser.add_argument(
        "--scenarios",
        default="baseline",
        help="Comma-separated scenario names or @path/to/scenarios.json",
    )
    parser.add_argument("--intensity", type=float, default=1.0)
    parser.add_argument("--report-interval", type=int, default=3)
    parser.add_argument("--node-pid", type=int, default=None)
    parser.add_argument("--no-tls", action="store_true")
    parser.add_argument("--tls-verify", action="store_true")
    parser.add_argument("--allow-nonlocal", action="store_true")
    return parser.parse_args()


def parse_scenarios(raw: str, duration: int, intensity: float) -> List[ScenarioConfig]:
    if raw.startswith("@"):
        path = Path(raw[1:])
        data = json.loads(path.read_text())
        scenarios = []
        for item in data:
            scenarios.append(
                ScenarioConfig(
                    name=item["name"],
                    enabled=item.get("enabled", True),
                    intensity=float(item.get("intensity", intensity)),
                    duration_sec=int(item.get("duration_sec", duration)),
                )
            )
        return scenarios

    names = [n.strip() for n in raw.split(",") if n.strip()]
    return [ScenarioConfig(name=n, duration_sec=duration, intensity=intensity) for n in names]


async def main() -> None:
    args = parse_args()
    scenarios = parse_scenarios(args.scenarios, args.duration, args.intensity)

    config = ToolkitConfig(
        node=NodeConfig(
            host=args.host,
            port=args.port,
            use_tls=not args.no_tls,
            tls_insecure=not args.tls_verify,
        ),
        metrics=MetricsConfig(report_interval_sec=args.report_interval, node_pid=args.node_pid),
        devices=args.devices,
        tps_target=args.tps,
        scenarios=scenarios,
    )

    if args.allow_nonlocal:
        config.safeguards.local_only = False

    orchestrator = ToolkitOrchestrator(config)
    await orchestrator.run()


if __name__ == "__main__":
    asyncio.run(main())
