package markers;

import io.helidon.webclient.api.WebClient;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.WebServerConfig;

public final class HelidonMain {
    private static WebServerConfig.Builder builder(int port) {
        return WebServer.builder().port(port).host("127.0.0.1").routing(routing -> routing.get("/", (request, response) -> response.send("ok")));
    }

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "client" -> {
                try {
                    WebClient.create().get().uri("http://127.0.0.1:1").request().close();
                } catch (RuntimeException e) {
                    System.out.println("client call failed: " + e.getClass().getSimpleName());
                }
            }
            case "configured" -> {
                builder(Support.freePort()).build();
                System.out.println("built");
            }
            case "serving" -> {
                WebServer server = builder(0).build().start();
                Support.answered(Support.get(server.port()));
                server.stop();
            }
            default -> throw new IllegalArgumentException(args[0]);
        }
        Support.finish();
    }
}
