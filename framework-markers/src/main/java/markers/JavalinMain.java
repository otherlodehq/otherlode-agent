package markers;

import io.javalin.Javalin;
import io.javalin.http.HttpStatus;

public final class JavalinMain {
    private static Javalin app() {
        return Javalin.create().get("/", ctx -> ctx.result("ok"));
    }

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "baseline" -> System.out.println("status: " + HttpStatus.OK.getCode());
            case "configured" -> {
                app();
                System.out.println("built");
            }
            case "serving" -> {
                int port = Support.freePort();
                Javalin app = app().start("127.0.0.1", port);
                Support.answered(Support.get(port));
                app.stop();
            }
            case "serving-empty" -> {
                int port = Support.freePort();
                Javalin app = Javalin.create().get("/", ctx -> {}).start("127.0.0.1", port);
                Support.answered(Support.get(port));
                app.stop();
            }
            default -> throw new IllegalArgumentException(args[0]);
        }
        Support.finish();
    }
}
