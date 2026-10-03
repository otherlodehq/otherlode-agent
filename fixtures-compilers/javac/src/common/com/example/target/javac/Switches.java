package com.example.target.javac;

public class Switches {
    public int enumStatement(Shade shade) {
        switch (shade) {
            case RED:
                return 1;
            case BLUE:
                return 3;
            default:
                return 0;
        }
    }

    public int enumExpression(Shade shade) {
        return switch (shade) {
            case RED -> 1;
            case GREEN -> 2;
            case BLUE -> 3;
        };
    }

    public int stringStatement(String status) {
        switch (status) {
            case "open":
                return 1;
            case "closed":
            case "done":
                return 2;
            case "Aa":
                return 3;
            case "BB":
                return 4;
            default:
                return 0;
        }
    }
}
