package com.example.target;

public class StaticInitBranchTarget {

    public static final int CHOSEN = Boolean.getBoolean("otherlode.test.flag") ? 1 : 2;

    public static int pick(int value) {
        if (value > 0) {
            return 1;
        }
        return 0;
    }
}
