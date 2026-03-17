package com.hybrid.blockchain;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Decodes raw byte results from smart contract execution into typed values.
 * Supports decoding {@code long}, {@code boolean}, and {@code address} return types.
 */
public final class ABIDecoder {

    private ABIDecoder() {}

    /**
     * Decodes a byte array result from contract execution into a typed value.
     *
     * @param result     the raw return bytes (typically the stack top after RETURN opcode)
     * @param returnType the expected return type string: "long", "boolean", "address", "void"
     * @return decoded value as Object (Long, Boolean, String), or null for "void"
     * @throws IllegalArgumentException if returnType is unknown or result is malformed
     */
    public static Object decode(byte[] result, String returnType) {
        if (result == null || result.length == 0) {
            return null;
        }
        switch (returnType.toLowerCase()) {
            case "long":
            case "int":
                if (result.length < 8) {
                    throw new IllegalArgumentException("Expected 8 bytes for long, got " + result.length);
                }
                return ByteBuffer.wrap(result, 0, 8).order(ByteOrder.BIG_ENDIAN).getLong();

            case "boolean":
                if (result.length < 1) {
                    throw new IllegalArgumentException("Expected at least 1 byte for boolean");
                }
                return result[result.length - 1] != 0;

            case "address":
                // Addresses are 20 bytes; treat as hex string
                return HexUtils.encode(result);

            case "void":
                return null;

            default:
                throw new IllegalArgumentException("Unknown return type: " + returnType);
        }
    }
}
