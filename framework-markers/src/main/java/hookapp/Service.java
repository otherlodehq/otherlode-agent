package hookapp;

public final class Service {
    private static int counter;

    private Service() {}

    public static int early() {
        counter += 1;
        return counter;
    }

    public static int late() {
        counter += 2;
        return counter;
    }
}
