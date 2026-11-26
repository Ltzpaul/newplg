package vn.ltzcore.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Shared money parser and compact formatter for Haven commands, menus and scoreboard. */
public final class MoneyFormat {
    private MoneyFormat() {}

    /**
     * Parses positive whole-number amounts with optional k/m/b suffixes.
     * Examples: 5000, 5k, 2.5m, 1b, $12.5K, 1,250.
     * Returns -1 for invalid, zero, negative or overflowing values.
     */
    public static long parse(String input) {
        if (input == null) return -1L;
        String value = input.trim().toLowerCase(Locale.ROOT)
                .replace("$", "")
                .replace(",", "")
                .replace("_", "")
                .replace(" ", "");
        if (value.isEmpty()) return -1L;

        long multiplier = 1L;
        char last = value.charAt(value.length() - 1);
        if (last == 'k' || last == 'm' || last == 'b') {
            multiplier = switch (last) {
                case 'k' -> 1_000L;
                case 'm' -> 1_000_000L;
                case 'b' -> 1_000_000_000L;
                default -> 1L;
            };
            value = value.substring(0, value.length() - 1);
        }

        // Decimal dot is supported; no exponent, sign, NaN or fractional-dollar results.
        if (!value.matches("(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)")) return -1L;
        try {
            long parsed = new BigDecimal(value)
                    .multiply(BigDecimal.valueOf(multiplier))
                    .setScale(0, RoundingMode.UNNECESSARY)
                    .longValueExact();
            return parsed > 0 ? parsed : -1L;
        } catch (ArithmeticException | NumberFormatException ex) {
            return -1L;
        }
    }

    /** Formats a balance in compact notation: 950, 1k, 2.5m, 1.2b. */
    public static String compact(long amount) {
        boolean negative = amount < 0;
        BigDecimal value = BigDecimal.valueOf(amount).abs();
        String suffix = "";
        BigDecimal divisor = BigDecimal.ONE;
        if (value.compareTo(BigDecimal.valueOf(1_000_000_000L)) >= 0) {
            divisor = BigDecimal.valueOf(1_000_000_000L); suffix = "b";
        } else if (value.compareTo(BigDecimal.valueOf(1_000_000L)) >= 0) {
            divisor = BigDecimal.valueOf(1_000_000L); suffix = "m";
        } else if (value.compareTo(BigDecimal.valueOf(1_000L)) >= 0) {
            divisor = BigDecimal.valueOf(1_000L); suffix = "k";
        }
        if (suffix.isEmpty()) return (negative ? "-" : "") + value.toPlainString();
        BigDecimal scaled = value.divide(divisor, 1, RoundingMode.HALF_UP).stripTrailingZeros();
        // Avoid awkward rounded results such as 1000k; promote to the next unit.
        if (scaled.compareTo(BigDecimal.valueOf(1_000)) >= 0 && suffix.equals("k")) {
            scaled = value.divide(BigDecimal.valueOf(1_000_000L), 1, RoundingMode.HALF_UP).stripTrailingZeros(); suffix = "m";
        } else if (scaled.compareTo(BigDecimal.valueOf(1_000)) >= 0 && suffix.equals("m")) {
            scaled = value.divide(BigDecimal.valueOf(1_000_000_000L), 1, RoundingMode.HALF_UP).stripTrailingZeros(); suffix = "b";
        }
        return (negative ? "-" : "") + scaled.toPlainString() + suffix;
    }
}
