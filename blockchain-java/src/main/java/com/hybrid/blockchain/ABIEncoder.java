package com.hybrid.blockchain;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Encodes function calls into bytecode-interpreter-compatible byte sequences.
 * The encoding format is: 4-byte function selector (FNV hash of name) followed by
 * 8 bytes per argument (long-encoded).
 *
 * <p>For address arguments, the address string is hashed and its first 8 bytes used.
 * For long arguments, the value is encoded directly as big-endian long.
 */
public final class ABIEncoder {

    private ABIEncoder() {}

    /**
     * Encodes a function call with the given arguments into a byte array.
     * The first 4 bytes are the function selector, followed by packed argument bytes.
     *
     * @param functionName the function name to call
     * @param args         arguments; supported types are Long (or Number), String (address)
     * @return encoded call bytes ready to pass as transaction data
     */
    public static byte[] encode(String functionName, Object... args) {
        int argCount = args == null ? 0 : args.length;
        ByteBuffer buf = ByteBuffer.allocate(4 + argCount * 8).order(ByteOrder.BIG_ENDIAN);

        // 4-byte function selector: FNV-1a hash of function name
        int selector = fnvHash(functionName);
        buf.putInt(selector);

        if (args != null) {
            for (Object arg : args) {
                if (arg instanceof Number) {
                    buf.putLong(((Number) arg).longValue());
                } else if (arg instanceof String) {
                    // Address: hash to 8 bytes
                    byte[] hash = Crypto.hash(((String) arg).getBytes(StandardCharsets.UTF_8));
                    buf.put(hash, 0, 8);
                } else {
                    buf.putLong(0L);
                }
            }
        }

        buf.flip();
        byte[] result = new byte[buf.remaining()];
        buf.get(result);
        return result;
    }

    /**
     * Encodes arguments defined by a ContractABI.FunctionDef.
     *
     * @param def  the function definition from the ABI
     * @param args arguments matching the function's parameterTypes
     * @return encoded call bytes
     */
    public static byte[] encodeFromABI(ContractABI.FunctionDef def, List<Object> args) {
        return encode(def.getName(), args.toArray());
    }

    private static int fnvHash(String s) {
        int hash = 0x811c9dc5;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xFF);
            hash *= 0x01000193;
        }
        return hash;
    }
}
