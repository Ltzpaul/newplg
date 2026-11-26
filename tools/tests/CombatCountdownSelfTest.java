import vn.ltzcore.util.CombatCountdown;

public final class CombatCountdownSelfTest {
    public static void main(String[] args) {
        long now = 1_000_000L;
        long until = CombatCountdown.refreshedUntil(now, 20);
        check(until == now + 20_000L, "hit must set a fresh 20-second expiry");
        check(CombatCountdown.secondsRemaining(until, now) == 20, "immediate display must show 20s");
        check(CombatCountdown.secondsRemaining(until, now + 1_001L) == 19, "countdown must tick down");
        long refreshed = CombatCountdown.refreshedUntil(now + 8_000L, 20);
        check(refreshed == now + 28_000L, "new hit must reset countdown to a fresh 20s");
        check(CombatCountdown.secondsRemaining(refreshed, now + 8_000L) == 20, "refreshed display must show 20s");
        check(CombatCountdown.secondsRemaining(until, until) == 0, "expired timer must return zero");
        System.out.println("CombatCountdownSelfTest PASS");
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
