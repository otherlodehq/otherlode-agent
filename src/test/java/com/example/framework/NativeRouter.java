package com.example.framework;

/** A framework class whose only hookable method is native, which Advice never weaves. */
public class NativeRouter {
    public native void spin();
}
