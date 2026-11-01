package dev.soaktest.shop;

import java.util.HashMap;
import java.util.Map;

public final class TaxTables {
    private static final Map<String, Double> RATES = new HashMap<>();

    static {
        RATES.put("UK", 0.2);
        RATES.put("DE", 0.19);
    }

    private TaxTables() {}

    public static double rate(String country) {
        return RATES.get(country);
    }
}
