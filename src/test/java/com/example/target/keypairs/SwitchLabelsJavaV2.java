package com.example.target.keypairs;

import com.example.target.SwitchColor;

public class SwitchLabelsJavaV2 {

    // Moves javac's switch map class from $1 to $2.
    private final Runnable first =
            new Runnable() {
                @Override
                public void run() {}
            };

    public int enumSwitch(SwitchColor color) {
        switch (color) {
            case GREEN:
                return 2;
            case RED:
                return 1;
            case BLUE:
                return 3;
            default:
                return 0;
        }
    }

    public int stringSwitch(String status) {
        switch (status) {
            case "new":
                return 5;
            case "open":
                return 1;
            case "closed":
                return 2;
            default:
                return 0;
        }
    }

    public int typeSwitch(Object value) {
        return switch (value) {
            case Integer i -> 2;
            case String s -> 1;
            case Long l -> 3;
            default -> 0;
        };
    }
}
