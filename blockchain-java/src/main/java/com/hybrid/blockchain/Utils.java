package com.hybrid.blockchain;

public final class Utils {
    private Utils() {}

    public static long safeLong(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number) return ((Number) o).longValue();
        try {
            return Long.parseLong(o.toString());
        } catch (Exception ex) {
            return 0L;
        }
    }
}
