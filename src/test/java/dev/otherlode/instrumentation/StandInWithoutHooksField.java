package dev.otherlode.instrumentation;

/** A class with a {@code runHooks} method and no {@code hooks} field, so the weave must refuse it. */
public final class StandInWithoutHooksField {
    private StandInWithoutHooksField() {}

    public static void runHooks() {}
}
