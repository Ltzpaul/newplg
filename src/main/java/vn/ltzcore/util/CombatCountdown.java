package vn.ltzcore.util;

/** Pure helper for the visible combat-tag countdown. */
public final class CombatCountdown {
    private CombatCountdown() {}

    /** Returns a ceil-rounded remaining second count; expired timestamps return zero. */
    public static long secondsRemaining(long untilMillis, long nowMillis) {
        long remaining = untilMillis - nowMillis;
        return remaining <= 0L ? 0L : (remaining + 999L) / 1000L;
    }

    /** Every valid hit replaces the previous expiry with a fresh duration. */
    public static long refreshedUntil(long nowMillis, int durationSeconds) {
        return nowMillis + Math.max(1, durationSeconds) * 1000L;
    }
}
