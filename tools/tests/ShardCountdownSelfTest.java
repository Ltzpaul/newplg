import vn.ltzcore.util.ShardCountdown;

public final class ShardCountdownSelfTest {
    public static void main(String[] args) {
        expect(60, ShardCountdown.secondsUntilNext(10_000L, 10_000L));
        expect(60, ShardCountdown.secondsUntilNext(10_000L, 10_001L));
        expect(59, ShardCountdown.secondsUntilNext(10_000L, 11_000L));
        expect(30, ShardCountdown.secondsUntilNext(10_000L, 40_000L));
        expect(1, ShardCountdown.secondsUntilNext(10_000L, 69_500L));
        expect(60, ShardCountdown.secondsUntilNext(10_000L, 70_000L));
        expect("01:00", ShardCountdown.format(60));
        expect("00:09", ShardCountdown.format(9));
        System.out.println("ShardCountdownSelfTest: PASS");
    }
    private static void expect(long expected, long actual) {
        if (expected != actual) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    private static void expect(String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
