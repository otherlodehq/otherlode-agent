package com.example.target;

import java.util.function.IntSupplier;
import java.util.function.Supplier;

public class ImplementedInterfaceJavaTarget {
    public Runnable lambda() {
        return () -> touch();
    }

    public Runnable anonymous() {
        return new Runnable() {
            @Override
            public void run() {
                touch();
            }
        };
    }

    public Object[] twoInterfaces() {
        IntSupplier asInt = this::touch;
        Supplier<Integer> boxed = this::touch;
        return new Object[] {asInt, boxed};
    }

    public int touch() {
        return 1;
    }
}
