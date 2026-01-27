package com.hybrid.blockchain;

import org.bouncycastle.util.encoders.Hex;

public final class HexUtils {
    private HexUtils() {
    }

    public static String encode(byte[] data) {
        if (data == null)
            return "0";
        return Hex.toHexString(data);
    }

    public static byte[] decode(String hex) {
        if (hex == null || hex.equals("0") || hex.isEmpty())
            return new byte[0];
        try {
            return Hex.decode(hex);
        } catch (Exception e) {
            return new byte[0];
        }
    }
}
