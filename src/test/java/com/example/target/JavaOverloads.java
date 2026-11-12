package com.example.target;

public class JavaOverloads {

    private final int amount;
    private final String currency;
    private final int rounding;

    public JavaOverloads(int amount) {
        this(amount, "GBP", 2);
    }

    public JavaOverloads(int amount, String currency) {
        this(amount, currency, 2);
    }

    public JavaOverloads(int amount, String currency, int rounding) {
        this.amount = amount;
        this.currency = currency;
        this.rounding = rounding;
    }

    public String format(int a) {
        return format(a, "x");
    }

    public String format(int a, String b) {
        return amount + " " + currency + " " + a + " " + b + " " + rounding;
    }
}
