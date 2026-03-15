import ipaddress
import socket

from .config import ToolkitConfig


def _is_local_or_private(host: str, allow_private: bool) -> bool:
    if host in {"localhost", "127.0.0.1", "::1"}:
        return True

    try:
        ip = ipaddress.ip_address(host)
    except ValueError:
        try:
            resolved = socket.gethostbyname(host)
            ip = ipaddress.ip_address(resolved)
        except Exception:
            return False

    if ip.is_loopback:
        return True
    if allow_private and ip.is_private:
        return True
    return False


def validate_safeguards(config: ToolkitConfig) -> None:
    host = config.node.host

    if config.safeguards.local_only and not _is_local_or_private(
        host,
        allow_private=config.safeguards.allow_private_subnets,
    ):
        raise ValueError(
            f"Refusing non-local target '{host}'. Disable local_only explicitly if needed."
        )

    if config.devices < 1:
        raise ValueError("devices must be >= 1")

    if config.devices > config.safeguards.max_devices:
        raise ValueError(
            f"devices {config.devices} exceeds safeguard limit {config.safeguards.max_devices}"
        )

    if config.tps_target < 1:
        raise ValueError("tps_target must be >= 1")
