package com.apklama.plus;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Limits /plus/verify calls per address, so nobody can spend the daily Bazaar API quota
 * (50,000 calls) by sending made-up purchase tokens.
 */
final class IpLimit {
    private final int max;
    private final long windowMs;
    private final ConcurrentHashMap<String, long[]> seen = new ConcurrentHashMap<>();

    IpLimit(int max, long windowMs) {
        this.max = max;
        this.windowMs = windowMs;
    }

    /** Counts one call; false when this address already made {@code max} calls in this window. */
    boolean allow(String ip, long now) {
        if (seen.size() > 50_000) seen.values().removeIf(w -> now - w[0] >= windowMs);
        long[] w = seen.compute(ip, (k, old) ->
                old == null || now - old[0] >= windowMs ? new long[]{now, 1} : new long[]{old[0], old[1] + 1});
        return w[1] <= max;
    }
}
