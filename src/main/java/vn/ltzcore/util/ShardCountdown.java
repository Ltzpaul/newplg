package vn.ltzcore.util;

/** Pure helpers for the one-shard-per-full-online-minute countdown. */
public final class ShardCountdown {
    private static final long INTERVAL_MILLIS = 60_000L;
    private ShardCountdown() {}

    public static long secondsUntilNext(long startedAtMillis, long nowMillis) {
        long elapsed = Math.max(0L, nowMillis - startedAtMillis);
        long remainder = elapsed % INTERVAL_MILLIS;
        long remainingMillis = INTERVAL_MILLIS - remainder;
        return Math.max(1L, (remainingMillis + 999L) / 1000L);
    }

    public static String format(long seconds) {
        long safe = Math.max(0L, seconds);
        long minutes = safe / 60L;
        long secs = safe % 60L;
        return String.format(java.util.Locale.ROOT, "%02d:%02d", minutes, secs);
    }
}
