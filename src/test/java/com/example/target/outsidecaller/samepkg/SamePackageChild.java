package com.example.target.outsidecaller.samepkg;

/** Overrides a package-private method of an out-of-scope class in its own package. */
public class SamePackageChild extends Hidden {
    @Override
    void quiet() {}
}
