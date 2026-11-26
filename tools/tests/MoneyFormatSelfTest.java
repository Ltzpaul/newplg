import vn.ltzcore.util.MoneyFormat;

public final class MoneyFormatSelfTest {
    public static void main(String[] args) {
        String[] inputs = {"5k", "5K", "2.5m", "1b", "1B", "$12.5K", "1,250", "0.1k", "1.234m"};
        long[] expected = {5_000L, 5_000L, 2_500_000L, 1_000_000_000L, 1_000_000_000L, 12_500L, 1_250L, 100L, 1_234_000L};
        for (int i = 0; i < inputs.length; i++) {
            long actual = MoneyFormat.parse(inputs[i]);
            if (actual != expected[i]) throw new AssertionError(inputs[i] + " -> " + actual + ", expected " + expected[i]);
        }
        for (String invalid : new String[]{"0", "-5k", "1.5", "1.5x", "NaN", "2bb"}) {
            if (MoneyFormat.parse(invalid) != -1L) throw new AssertionError("Invalid input accepted: " + invalid);
        }
        expect("950", MoneyFormat.compact(950));
        expect("1k", MoneyFormat.compact(1_000));
        expect("2.5m", MoneyFormat.compact(2_500_000));
        expect("1m", MoneyFormat.compact(999_999));
        expect("1.5b", MoneyFormat.compact(1_500_000_000L));
        System.out.println("MoneyFormatSelfTest: PASS");
    }
    private static void expect(String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
