package com.hybrid.blockchain;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * [HC-090] Deterministic binary encoding of the object trees that make up part of the state root
 * (identity registry, device registry, nullifiers, jail list).
 *
 * <p>Those trees used to be hashed as Jackson JSON text, so the consensus bytes depended on a JSON
 * library's output (number formatting, escaping, sort settings). This encoding depends only on the
 * values:</p>
 * <ul>
 *   <li>every value starts with a type tag; strings, byte arrays, lists and maps are length- or
 *       count-prefixed, so the encoding is injective;</li>
 *   <li>all integer types encode as int64, so {@code Integer 5} and {@code Long 5} are the same
 *       (Jackson reads small numbers back as Integer);</li>
 *   <li>doubles encode by their IEEE-754 bits; a float as the double Jackson reads back for it;</li>
 *   <li>map entries are sorted by the UTF-8 bytes of the key's string form, whatever the map type;
 *       a {@link Set} is sorted by the encoding of its elements;</li>
 *   <li>any other type is refused, never encoded ambiguously.</li>
 * </ul>
 */
public final class CanonicalEncoding {

    private static final int NULL = 0, FALSE = 1, TRUE = 2, INT = 3, DOUBLE = 4, STRING = 5,
            BYTES = 6, LIST = 7, MAP = 8, DECIMAL = 9;

    private CanonicalEncoding() {
    }

    public static byte[] encode(Object value) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            write(out, value);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);   // in-memory stream
        }
    }

    private static void write(DataOutputStream out, Object v) throws IOException {
        if (v == null) {
            out.writeByte(NULL);
        } else if (v instanceof Boolean) {
            out.writeByte((Boolean) v ? TRUE : FALSE);
        } else if (v instanceof Byte || v instanceof Short || v instanceof Integer || v instanceof Long) {
            out.writeByte(INT);
            out.writeLong(((Number) v).longValue());
        } else if (v instanceof BigInteger) {
            BigInteger b = (BigInteger) v;
            if (b.bitLength() < 64) {
                out.writeByte(INT);
                out.writeLong(b.longValue());
            } else {
                out.writeByte(DECIMAL);
                writeUtf8(out, b.toString());
            }
        } else if (v instanceof Float) {
            out.writeByte(DOUBLE);
            out.writeLong(Double.doubleToLongBits(Double.parseDouble(v.toString())));
        } else if (v instanceof Double) {
            out.writeByte(DOUBLE);
            out.writeLong(Double.doubleToLongBits((Double) v));
        } else if (v instanceof BigDecimal) {
            out.writeByte(DECIMAL);
            writeUtf8(out, ((BigDecimal) v).stripTrailingZeros().toPlainString());
        } else if (v instanceof CharSequence || v instanceof Enum) {
            out.writeByte(STRING);
            writeUtf8(out, v instanceof Enum ? ((Enum<?>) v).name() : v.toString());
        } else if (v instanceof byte[]) {
            byte[] b = (byte[]) v;
            out.writeByte(BYTES);
            out.writeInt(b.length);
            out.write(b);
        } else if (v instanceof Map) {
            writeMap(out, (Map<?, ?>) v);
        } else if (v instanceof Set) {
            List<byte[]> items = new ArrayList<>();
            for (Object o : (Set<?>) v) items.add(encode(o));
            items.sort(Arrays::compareUnsigned);
            out.writeByte(LIST);
            out.writeInt(items.size());
            for (byte[] item : items) out.write(item);
        } else if (v instanceof Collection) {
            Collection<?> c = (Collection<?>) v;
            out.writeByte(LIST);
            out.writeInt(c.size());
            for (Object o : c) write(out, o);
        } else if (v instanceof Object[]) {
            write(out, Arrays.asList((Object[]) v));
        } else {
            throw new IllegalArgumentException("not canonically encodable: " + v.getClass().getName());
        }
    }

    private static void writeMap(DataOutputStream out, Map<?, ?> m) throws IOException {
        TreeMap<byte[], Object> sorted = new TreeMap<>(Arrays::compareUnsigned);
        for (Map.Entry<?, ?> e : m.entrySet()) {
            byte[] key = String.valueOf(e.getKey()).getBytes(StandardCharsets.UTF_8);
            if (sorted.containsKey(key)) {
                throw new IllegalArgumentException("two map keys with the same string form: " + e.getKey());
            }
            sorted.put(key, e.getValue());
        }
        out.writeByte(MAP);
        out.writeInt(sorted.size());
        for (Map.Entry<byte[], Object> e : sorted.entrySet()) {
            out.writeInt(e.getKey().length);
            out.write(e.getKey());
            write(out, e.getValue());
        }
    }

    private static void writeUtf8(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
    }
}
