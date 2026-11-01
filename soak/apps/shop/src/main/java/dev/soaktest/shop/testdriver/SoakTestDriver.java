package dev.soaktest.shop.testdriver;

import dev.soaktest.shop.Pricing;

public final class SoakTestDriver {
    private SoakTestDriver() {}

    public static void main(String[] args) {
        new Pricing().applyLegacyDiscount(100.0, 5);
    }
}
