package hookapp;

/**
 * An application with its own shutdown hook. The hook sleeps, then runs in-scope code, and the JVM
 * exits normally. In mode "method" the hook calls a method of a class loaded earlier. In any other
 * mode the hook loads a class for the first time.
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
