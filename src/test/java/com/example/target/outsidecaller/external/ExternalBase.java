package com.example.target.outsidecaller.external;

/** A superclass outside scope, with one member of each access shape the override walk tests. */
public abstract class ExternalBase {
    public void ping() {}

    protected void hook() {}

    public static void util() {}

    private void secret() {}
}
