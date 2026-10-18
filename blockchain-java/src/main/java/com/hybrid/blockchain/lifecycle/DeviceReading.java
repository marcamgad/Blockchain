package com.hybrid.blockchain.lifecycle;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hybrid.blockchain.AccountState;
import com.hybrid.blockchain.Crypto;
import com.hybrid.blockchain.HexUtils;
import com.hybrid.blockchain.identity.DecentralizedIdentifier;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * [HC-013] A sensor reading signed end-to-end by the DEVICE's own key.
 *
 * <p>Gateways (MQTT/CoAP ingress) forward readings on behalf of devices. Previously nothing proved
 * a message came from the device it named, so anyone able to publish on the broker could
 * attribute data to any device. Now the device signs
 * {@code H("HC-IOT-READING-v1", deviceId, payload, timestamp)} with the key registered in its DID;
 * the gateway only relays it and pays the fee. Validation accepts the reading from any sender iff
 * this signature verifies against the device's registered key and the timestamp is within
 * {@link #MAX_SKEW_MS} of the block time (bounds replay of an old signed reading).
 *
 * <p>Wire form (JSON, the transaction's {@code data}):
 * {@code {"deviceId":"...","payload":"<hex>","timestamp":<ms>,"signature":"<hex 64 bytes>"}}.
 */
public final class DeviceReading {

    /** Accepted distance between the device's timestamp and the including block's timestamp. */
    public static final long MAX_SKEW_MS = 10 * 60 * 1000L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public final String deviceId;
    public final String payloadHex;
    public final long timestamp;
    public final String signatureHex;

    @JsonCreator
    public DeviceReading(@JsonProperty("deviceId") String deviceId,
                         @JsonProperty("payload") String payloadHex,
                         @JsonProperty("timestamp") long timestamp,
                         @JsonProperty("signature") String signatureHex) {
        this.deviceId = deviceId;
        this.payloadHex = payloadHex;
        this.timestamp = timestamp;
        this.signatureHex = signatureHex;
    }

    @JsonProperty("payload") public String getPayload() { return payloadHex; }
    @JsonProperty("signature") public String getSignature() { return signatureHex; }

    /** The digest the device signs. */
    public static byte[] digest(String deviceId, byte[] payload, long timestamp) {
        return Crypto.hashWithDomain("HC-IOT-READING-v1",
                deviceId.getBytes(StandardCharsets.UTF_8), payload,
                ByteBuffer.allocate(8).putLong(timestamp).array());
    }

    /** Device-side helper: builds the signed envelope (used by device SDKs and tests). */
    public static DeviceReading sign(String deviceId, byte[] payload, long timestamp, BigInteger devicePrivateKey) {
        byte[] sig = Crypto.sign(digest(deviceId, payload, timestamp), devicePrivateKey);
        return new DeviceReading(deviceId, HexUtils.encode(payload), timestamp, HexUtils.encode(sig));
    }

    public byte[] toJson() {
        try {
            return MAPPER.writeValueAsBytes(this);
        } catch (Exception e) {
            throw new IllegalStateException("cannot encode reading", e);
        }
    }

    /** Parses the envelope, or returns null if {@code data} is not one. */
    public static DeviceReading parse(byte[] data) {
        if (data == null || data.length == 0 || data[0] != '{') return null;
        try {
            DeviceReading r = MAPPER.readValue(data, DeviceReading.class);
            return (r.deviceId == null || r.payloadHex == null || r.signatureHex == null) ? null : r;
        } catch (Exception e) {
            return null;
        }
    }

    public byte[] payload() {
        return HexUtils.decode(payloadHex);
    }

    /**
     * True iff the reading is signed by the key registered for {@code deviceId} in {@code state}
     * and its timestamp is within {@link #MAX_SKEW_MS} of {@code blockTimestamp}.
     */
    public boolean verify(AccountState state, String expectedDeviceId, long blockTimestamp) {
        if (!deviceId.equals(expectedDeviceId)) return false;
        if (Math.abs(blockTimestamp - timestamp) > MAX_SKEW_MS) return false;
        byte[] pubKey = registeredKey(state, deviceId);
        if (pubKey == null) return false;
        return Crypto.verify(digest(deviceId, payload(), timestamp), HexUtils.decode(signatureHex), pubKey);
    }

    /** The device's registered public key (first verification method of its DID), or null. */
    public static byte[] registeredKey(AccountState state, String deviceId) {
        try {
            DeviceLifecycleManager.DeviceRecord rec = state.getLifecycleManager().getDeviceRecord(deviceId);
            if (rec == null || rec.getDid() == null) return null;
            DecentralizedIdentifier did = state.getSSIManager().resolveDID(rec.getDid());
            if (did == null || did.getVerificationMethods().isEmpty()) return null;
            return HexUtils.decode(did.getVerificationMethods().get(0).getPublicKeyHex());
        } catch (Exception e) {
            return null;
        }
    }
}
