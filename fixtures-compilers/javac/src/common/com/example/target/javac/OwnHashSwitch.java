package com.example.target.javac;

public final class OwnHashSwitch {
    private OwnHashSwitch() {
    }

    public static int pick(String s) {
        switch (s.hashCode()) {
            case 97:
                if (s.equals("a")) {
                    return 1;
                }
                break;
            case 98:
                if (s.equals("b")) {
                    return 2;
                }
                break;
            default:
                break;
        }
        return 0;
    }
}
