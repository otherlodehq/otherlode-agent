package com.example.target;

public class SwitchThenBranchTarget {

    public int classify(int value) {
        switch (value) {
            case 1:
                return 10;
            case 1000:
                return 20;
            default:
                break;
        }
        if (value > 5000) {
            return 30;
        }
        return 40;
    }
}
