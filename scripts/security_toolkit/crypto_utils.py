import hashlib
import json
import time
import secrets
from dataclasses import dataclass
from typing import Dict

import ecdsa


def _canonical_tx_bytes(tx: Dict) -> bytes:
    return json.dumps(tx, sort_keys=True, separators=(",", ":")).encode()


@dataclass
class IoTIdentity:
    device_id: str
    did: str
    signing_key: ecdsa.SigningKey
    verifying_key: ecdsa.VerifyingKey
    nonce: int = 0

    @classmethod
    def create(cls, device_id: str) -> "IoTIdentity":
        signing_key = ecdsa.SigningKey.generate(curve=ecdsa.SECP256k1)
        verifying_key = signing_key.verifying_key
        did = f"did:hybrid:{device_id}"
        return cls(device_id=device_id, did=did, signing_key=signing_key, verifying_key=verifying_key)

    def rotate(self) -> None:
        suffix = secrets.token_hex(4)
        self.device_id = f"{self.device_id.split('#')[0]}#{suffix}"
        self.did = f"did:hybrid:{self.device_id}"
        self.signing_key = ecdsa.SigningKey.generate(curve=ecdsa.SECP256k1)
        self.verifying_key = self.signing_key.verifying_key
        self.nonce = 0

    def sign(self, payload: bytes) -> bytes:
        return self.signing_key.sign(payload, hashfunc=hashlib.sha256)

    def create_transaction(self, to_did: str, amount: int, data: str, tx_type: str = "ACCOUNT") -> Dict:
        tx = {
            "from": self.did,
            "to": to_did,
            "amount": amount,
            "nonce": self.nonce,
            "timestamp": int(time.time() * 1000),
            "data": data,
            "type": tx_type,
        }
        tx_msg = _canonical_tx_bytes(tx)
        tx["signature"] = self.sign(tx_msg).hex()
        tx["pubKey"] = self.verifying_key.to_string("compressed").hex()
        self.nonce += 1
        return tx
