package com.example.outside.named;

/** The class-retention container javac writes when {@link ClassRepeated} is used twice. */
public @interface ClassRepeatedList {
    ClassRepeated[] value();
}
