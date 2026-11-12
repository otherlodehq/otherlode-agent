package com.example.target.javac;

public record Named(String name, int age) {
    @Override
    public boolean equals(Object other) {
        return other instanceof Named n && n.age == age;
    }
}
