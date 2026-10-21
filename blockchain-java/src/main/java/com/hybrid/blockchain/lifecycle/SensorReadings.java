package com.hybrid.blockchain.lifecycle;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hybrid.blockchain.Crypto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;

/**
 * [HC-058] Deterministic sensor values for contracts.
 *
 * <p>Contracts used to read sensors from each node's local {@code HardwareManager}, so two nodes
 * with different hardware computed different state roots. Contracts now read the latest
 * device-authenticated TELEMETRY value recorded in chain state:
 * <ul>
 *   <li>Sensor id of a device = first 8 bytes (big-endian) of
 *       {@code hashWithDomain("HYBRIDCHAIN-SENSOR-ID-v1", deviceId)}. See {@link #sensorId}.</li>
 *   <li>Value = the reading in milli-units: a payload of {@code 21.5} or {@code {"value":21.5}}
 *       is stored as {@code 21500}. Parsed as a decimal, never as binary floating point.</li>
 *   <li>No reading yet: {@link #NO_READING} ({@code Long.MIN_VALUE}).</li>
 *   <li>"Latest" = the last value written by an earlier transaction in chain order, including
 *       earlier transactions of the same block.</li>
 * </ul>
 */
public final class SensorReadings {

    /** Returned to contracts when a sensor has no recorded reading. */
    public static final long NO_READING = Long.MIN_VALUE;

    private static final ObjectMapper DECIMAL_MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private SensorReadings() {
    }

    /** The numeric sensor id contracts use for a device. */
    public static long sensorId(String deviceId) {
        byte[] h = Crypto.hashWithDomain("HYBRIDCHAIN-SENSOR-ID-v1", deviceId);
        return ByteBuffer.wrap(h, 0, 8).getLong();
    }

    /**
     * The reading in milli-units, or null if the payload is not a number (or a JSON object with a
     * numeric {@code value}), or does not fit a long.
     */
    public static Long parseMilli(byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > 4096) return null;
        try {
            JsonNode n = DECIMAL_MAPPER.readTree(payload);
            if (n != null && n.isObject()) n = n.get("value");
            if (n == null || !n.isNumber()) return null;
            BigDecimal milli = n.decimalValue().movePointRight(3).setScale(0, RoundingMode.HALF_EVEN);
            long v = milli.longValueExact();
            return v == NO_READING ? null : v;
        } catch (Exception e) {
            return null;
        }
    }
}
