package markers;

import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.RoutingHandler;

public final class UndertowMain {
    private static final HttpHandler PLAIN = exchange -> exchange.getResponseSender().send("ok");

    private static Undertow server(int port, HttpHandler handler) {
        return Undertow.builder().addHttpListener(port, "127.0.0.1").setHandler(handler).build();
    }

    private static RoutingHandler routing() {
        return Handlers.routing().get("/", PLAIN);
    }

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "baseline" -> {
                int port = Support.freePort();
                Undertow server = server(port, Handlers.path(PLAIN).addPrefixPath("/plain", PLAIN));
                server.start();
                Support.answered(Support.get(port));
                server.stop();
            }
            case "configured" -> {
                server(Support.freePort(), routing());
                System.out.println("built");
            }
            case "serving" -> {
                int port = Support.freePort();
                Undertow server = server(port, routing());
                server.start();
                Support.answered(Support.get(port));
                server.stop();
            }
            default -> throw new IllegalArgumentException(args[0]);
        }
        Support.finish();
    }
}
