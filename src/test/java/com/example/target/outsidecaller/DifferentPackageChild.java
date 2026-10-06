package com.example.target.outsidecaller;

import com.example.target.outsidecaller.external.ExternalPackagePrivateBase;

/** Declares a method with the name of a package-private method in another package, which overrides nothing. */
public class DifferentPackageChild extends ExternalPackagePrivateBase {
    void quiet() {}
}
