package com.example.target.outsidecaller;

import com.example.target.outsidecaller.external.ExternalBase;

/** Declares a static method that hides an out-of-scope static one, and an instance method beside a private one. */
public class Hiding extends ExternalBase {
    public static void util() {}

    public void secret() {}
}
