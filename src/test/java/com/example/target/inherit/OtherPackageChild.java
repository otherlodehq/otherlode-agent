package com.example.target.inherit;

import com.example.target.inherit.pp.PackageBase;

/** Declares a method of the same name and descriptor, which does not override a package-private one from another package. */
public class OtherPackageChild extends PackageBase {
    void quiet() {}
}
