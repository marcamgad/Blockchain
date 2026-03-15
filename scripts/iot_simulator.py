"""Compatibility wrapper for the new security toolkit runner.

Use this module exactly like before:
    python3 scripts/iot_simulator.py [args...]

All arguments are forwarded to `scripts/security_toolkit_runner.py`.
"""

from security_toolkit_runner import main


if __name__ == "__main__":
    import asyncio

    asyncio.run(main())
