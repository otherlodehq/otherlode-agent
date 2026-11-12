package com.example.target;

import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;

public class LambdaTarget {

    public int classifyViaLambda(int value) {
        IntUnaryOperator classify =
                v -> {
                    if (v > 0) {
                        return 1;
                    }
                    return -1;
                };
        return classify.applyAsInt(value);
    }

    public String shipViaMethodReference() {
        Supplier<String> supplier = this::ship;
        return supplier.get();
    }

    public String ship() {
        return "shipped";
    }
}
