package markers;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.Server;

public final class ArmeriaMain {
    private static Server server(int port) {
        return Server.builder().http(port).service("/", (ctx, req) -> HttpResponse.of("ok")).build();
    }

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "client" -> {
                try {
                    WebClient.of("http://127.0.0.1:1").get("/").aggregate().join();
                } catch (RuntimeException e) {
                    System.out.println("client call failed: " + e.getClass().getSimpleName());
                }
            }
            case "configured" -> {
                server(Support.freePort());
                System.out.println("built");
            }
            case "serving" -> {
                int port = Support.freePort();
                Server server = server(port);
                server.start().join();
                Support.answered(WebClient.of("http://127.0.0.1:" + port).get("/").aggregate().join().contentUtf8());
                server.stop().join();
            }
            default -> throw new IllegalArgumentException(args[0]);
        }
        Support.finish();
    }
}
