package com.example.target.javac;

/** A record with a hand-written equals and generated hashCode and toString. */
public record Named(String name, int age) {
    @Override
    public boolean equals(Object other) {
        return other instanceof Named n && n.age == age;
    }
}
