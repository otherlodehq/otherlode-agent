package com.example.target;

import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;

public class CreationEdgeJavaTarget {
    private final int base = 10;

    public int capturing(int offset) {
        IntUnaryOperator op = v -> v + offset;
        return op.applyAsInt(1);
    }

    public int capturingThis() {
        IntUnaryOperator op = v -> v + base;
        return op.applyAsInt(1);
    }

    public Supplier<String> boundReference() {
        return this::name;
    }

    public Function<CreationEdgeJavaTarget, String> unboundReference() {
        return CreationEdgeJavaTarget::name;
    }

    public IntSupplier staticReference() {
        return CreationEdgeJavaTarget::constant;
    }

    public Function<String, Box> nestedConstructorReference() {
        return Box::new;
    }

    public Supplier<Inner> innerConstructorReference() {
        return Inner::new;
    }

    public String name() {
        return "name";
    }

    public static int constant() {
        return 1;
    }

    public static class Box {
        public Box(String label) {
        }
    }

    public class Inner {
    }
}
