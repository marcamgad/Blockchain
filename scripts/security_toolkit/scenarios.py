import base64
import json
import random
import time
from dataclasses import dataclass
from typing import Any, Dict, Optional


@dataclass
class ScenarioState:
    replay_cache: list
    fuzz_counter: int = 0


def apply_scenario(
    scenario_name: str,
    p2p_message: Dict[str, Any],
    tx: Dict[str, Any],
    state: ScenarioState,
    intensity: float,
) -> Optional[Dict[str, Any]]:
    if scenario_name in {"baseline", "telemetry"}:
        return p2p_message

    if scenario_name == "gossip_pressure":
        return p2p_message

    if scenario_name == "replay_validation":
        chance = min(0.95, 0.10 * max(1.0, intensity))
        if state.replay_cache and random.random() < chance:
            return random.choice(state.replay_cache)
        state.replay_cache.append(dict(p2p_message))
        if len(state.replay_cache) > 10_000:
            del state.replay_cache[:2_000]
        return p2p_message

    if scenario_name == "malformed_fuzz":
        return _fuzz_message(p2p_message, tx, state, intensity)

    if scenario_name == "signature_spam":
        corrupted = dict(p2p_message)
        if random.random() < min(0.98, 0.15 * max(1.0, intensity)):
            corrupted["signature"] = base64.b64encode(random.randbytes(64)).decode()
        return corrupted

    if scenario_name == "did_spoof":
        spoofed = dict(p2p_message)
        if random.random() < min(0.9, 0.2 * max(1.0, intensity)):
            spoofed["senderId"] = "did:hybrid:spoofed-device"
            tx_payload = json.loads(base64.b64decode(spoofed["payload"]).decode())
            tx_payload["from"] = "did:hybrid:spoofed-device"
            spoofed["payload"] = base64.b64encode(json.dumps(tx_payload).encode()).decode()
        return spoofed

    if scenario_name == "identity_replay":
        replayed = dict(p2p_message)
        tx_payload = json.loads(base64.b64decode(replayed["payload"]).decode())
        tx_payload["timestamp"] = int(time.time() * 1000) - 600_000
        replayed["payload"] = base64.b64encode(json.dumps(tx_payload).encode()).decode()
        return replayed

    if scenario_name == "invalid_pubkey":
        tampered = dict(p2p_message)
        tx_payload = json.loads(base64.b64decode(tampered["payload"]).decode())
        tx_payload["pubKey"] = "00"
        tampered["payload"] = base64.b64encode(json.dumps(tx_payload).encode()).decode()
        return tampered

    if scenario_name == "contract_gas_exhaustion":
        return _contract_payload(p2p_message, "GAS_EXHAUSTION")

    if scenario_name == "contract_infinite_loop":
        return _contract_payload(p2p_message, "INFINITE_LOOP")

    if scenario_name == "contract_large_state":
        return _contract_payload(p2p_message, "LARGE_STATE_WRITE")

    if scenario_name == "contract_reentrancy":
        return _contract_payload(p2p_message, "REENTRANCY")

    return p2p_message


def _fuzz_message(
    p2p_message: Dict[str, Any],
    tx: Dict[str, Any],
    state: ScenarioState,
    intensity: float,
) -> Dict[str, Any]:
    mutated = dict(p2p_message)
    state.fuzz_counter += 1
    fuzz_choice = state.fuzz_counter % 6

    if fuzz_choice == 0:
        mutated.pop("timestamp", None)
    elif fuzz_choice == 1:
        tx2 = dict(tx)
        tx2["amount"] = -1
        mutated["payload"] = base64.b64encode(json.dumps(tx2).encode()).decode()
    elif fuzz_choice == 2:
        mutated["payload"] = "NOT_BASE64"
    elif fuzz_choice == 3:
        tx2 = dict(tx)
        tx2["nonce"] = "bad-nonce"
        mutated["payload"] = base64.b64encode(json.dumps(tx2).encode()).decode()
    elif fuzz_choice == 4:
        tx2 = dict(tx)
        tx2["data"] = "X" * int(1024 * 64 * max(1, intensity))
        mutated["payload"] = base64.b64encode(json.dumps(tx2).encode()).decode()
    else:
        mutated["type"] = 12345

    return mutated


def _contract_payload(p2p_message: Dict[str, Any], attack_kind: str) -> Dict[str, Any]:
    mutated = dict(p2p_message)
    tx_payload = json.loads(base64.b64decode(mutated["payload"]).decode())
    tx_payload["type"] = "CONTRACT_CALL"
    tx_payload["data"] = json.dumps(
        {
            "contract": "diagnostics.wasm",
            "method": attack_kind,
            "gasLimit": 10_000_000,
            "args": {"probe": True},
        }
    )
    mutated["payload"] = base64.b64encode(json.dumps(tx_payload).encode()).decode()
    return mutated
