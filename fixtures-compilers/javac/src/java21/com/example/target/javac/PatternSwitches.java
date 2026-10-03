package com.example.target.javac;

public class PatternSwitches {
    sealed interface Shape permits Square, Circle {}

    record Square(int side) implements Shape {}

    record Circle(int radius) implements Shape {}

    public int objectPattern(Object value) {
        return switch (value) {
            case String s -> s.length();
            case Integer i -> i;
            default -> 0;
        };
    }

    public int sealedPattern(Shape shape) {
        return switch (shape) {
            case Square q -> q.side();
            case Circle c -> c.radius();
        };
    }
}
