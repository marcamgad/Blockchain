# Signing device readings (MQTT / CoAP telemetry)

Since audit fix **HC-013**, every telemetry reading must be signed by the **device's own key**
(the key registered in its DID when the device was activated). The gateway node only relays it
and pays the transaction fee. Readings that are unsigned, signed by another key, addressed to a
different device, older than 10 minutes, or modified after signing are rejected at the gateway
and again by every validator.

## Message format

Publish to `blockchain/iot/<deviceId>/telemetry` (MQTT) or POST to the CoAP telemetry resource:

```json
{
  "deviceId":  "thermo-42",
  "payload":   "32312e35",
  "timestamp": 1767312000000,
  "signature": "<128 hex chars>"
}
```

| Field | Meaning |
|---|---|
| `deviceId` | Must equal the device id in the topic / resource. |
| `payload` | The reading itself, hex-encoded bytes (e.g. `"21.5"` → `32312e35`). |
| `timestamp` | Milliseconds since epoch, from the device clock. Must be within ±10 minutes of the block time. |
| `signature` | 64 bytes, `r ‖ s` (32 bytes each, big-endian, **low-S**), secp256k1. |

## What is signed

```
digest = SHA-256( "HC-IOT-READING-v1" ‖ 0x00
                  ‖ len(deviceId) ‖ deviceId_utf8
                  ‖ len(payload)  ‖ payload_bytes
                  ‖ len(ts)       ‖ ts )
```
`len(x)` is a 4-byte big-endian length; `ts` is the timestamp as an 8-byte big-endian integer
(so `len(ts)` is always `00 00 00 08`). The device signs `digest` directly (ECDSA over the 32-byte
digest, RFC 6979 deterministic nonce recommended), and normalises `s` to the lower half of the
curve order.

## Reference implementations

**Java** (on the node, or a Java gateway SDK):
```java
DeviceReading r = DeviceReading.sign(deviceId, payloadBytes, System.currentTimeMillis(), devicePrivateKey);
mqttClient.publish("blockchain/iot/" + deviceId + "/telemetry", new MqttMessage(r.toJson()));
```

**Python** (`pip install ecdsa`):
```python
import hashlib, json, struct, time
from ecdsa import SigningKey, SECP256k1
from ecdsa.util import sigencode_strings_canonize

def lp(b): return struct.pack(">I", len(b)) + b

def sign_reading(device_id: str, payload: bytes, sk: SigningKey) -> bytes:
    ts = int(time.time() * 1000)
    msg = (b"HC-IOT-READING-v1\x00" + lp(device_id.encode()) + lp(payload)
           + lp(struct.pack(">q", ts)))
    digest = hashlib.sha256(msg).digest()
    r, s = sk.sign_digest_deterministic(digest, hashfunc=hashlib.sha256,
                                        sigencode=sigencode_strings_canonize)   # low-S
    return json.dumps({"deviceId": device_id, "payload": payload.hex(),
                       "timestamp": ts, "signature": (r + s).hex()}).encode()
```

## Notes
- The device's clock must be roughly right (±10 min). Devices without an RTC should sync via NTP
  or take the time from the gateway before signing.
- A signed reading can be relayed by any gateway; it cannot be redirected to another device or
  altered. Within the 10-minute window a gateway could resubmit the same reading. Applications
  that need strict once-only delivery should include a per-device counter in `payload`.
