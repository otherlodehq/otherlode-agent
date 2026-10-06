package com.example.target.outsidecaller.samepkg;

/** Out of scope by an exclude rule on its own name, in the same package as its in-scope subclass. */
public abstract class Hidden {
    void quiet() {}
}
