package com.rideledger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Locale;

final class Money {
    private Money() {}

    static String code(String raw) {
        String code = raw.trim().toUpperCase(Locale.ROOT);
        if ("RMB".equals(code)) code = "CNY";
        if (!code.matches("[A-Z]{3}"))
            throw new IllegalArgumentException("请输入三位货币代码，例如 CNY");
        return code;
    }

    static int scale(String code) {
        try {
            int scale = Currency.getInstance(code).getDefaultFractionDigits();
            return scale < 0 ? 2 : scale;
        } catch (IllegalArgumentException ignored) { return 2; }
    }

    static long parse(String raw, String code) {
        BigDecimal value = new BigDecimal(raw.trim());
        if (value.signum() <= 0) throw new IllegalArgumentException("金额必须大于零");
        return value.movePointRight(scale(code)).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
    }

    static String format(long minor, String code) {
        return BigDecimal.valueOf(minor, scale(code)).toPlainString() + " " + code;
    }

    static long shareA(long minor, int basisPoints) {
        return BigDecimal.valueOf(minor).multiply(BigDecimal.valueOf(basisPoints))
                .divide(BigDecimal.valueOf(10000), 0, RoundingMode.HALF_UP).longValueExact();
    }
}

