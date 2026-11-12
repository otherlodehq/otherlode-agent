package com.example.target;

// The interface is package-private, so the JDK defines its proxy in this package, inside the
// fixture include prefix.
interface PackagePrivateGreeter {
    String greet();
}
