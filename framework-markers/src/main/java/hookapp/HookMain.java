package hookapp;

/**
 * An application with its own shutdown hook, as a framework's context close has. The hook sleeps,
 * then runs in-scope code, and the JVM exits normally. Modes: "method" calls a method of a class
 * loaded earlier, "class" first loads a class there.
 */
public final class HookMain {
    private HookMain() {}

    public static void main(String[] args) {
        String mode = args[0];
        Service.early();
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    try {
                                        Thread.sleep(1500);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                    if (mode.equals("method")) {
                                        Service.late();
                                    } else {
                                        LateLoaded.run();
                                    }
                                    System.out.println("app hook ran " + mode);
                                },
                                "app-shutdown-hook"));
        System.out.println("main done");
    }
}
