package hookapp;

/** First loaded by the application's shutdown hook. */
public final class LateLoaded {
    private static int counter;

    private LateLoaded() {}

    public static int run() {
        counter += 3;
        return counter;
    }
}
