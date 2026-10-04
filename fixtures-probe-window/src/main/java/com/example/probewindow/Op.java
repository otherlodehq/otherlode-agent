package com.example.probewindow;

public enum Op {
    PLUS {
        @Override
        int apply(int a, int b) {
            return a + b;
        }
    },
    TIMES {
        @Override
        int apply(int a, int b) {
            if (a == 0) {
                return 0;
            }
            return a * b;
        }
    };

    abstract int apply(int a, int b);
}
