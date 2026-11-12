package com.example.target;

public class SignatureJavaTarget {

    private final String name;

    public SignatureJavaTarget(String name, long seed) {
        this.name = name + seed;
    }

    public static String join(String left, long count, double ratio, int last) {
        return left + count + ratio + last;
    }

    public String greet(String greeting) {
        String shown = greeting + " " + name;
        return shown;
    }
}
