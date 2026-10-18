package markers;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.netty.DisposableServer;

public final class WebFluxMain {
    private static RouterFunction<ServerResponse> router() {
        return RouterFunctions.route()
                .GET("/", request -> ServerResponse.ok().bodyValue("ok"))
                .build();
    }

    @Configuration
    @EnableWebFlux
    static class DispatcherConfig {
        @Bean
        RouterFunction<ServerResponse> routes() {
            return router();
        }
    }

    private static String fetch(int port) {
        return WebClient.create("http://127.0.0.1:" + port).get().uri("/").retrieve().bodyToMono(String.class).block();
    }

    private static void serve(HttpHandler handler) throws Exception {
        int port = Support.freePort();
        DisposableServer server =
                reactor.netty.http.server.HttpServer.create()
                        .host("127.0.0.1")
                        .port(port)
                        .handle(new ReactorHttpHandlerAdapter(handler))
                        .bindNow();
        Support.answered(fetch(port));
        server.disposeNow();
    }

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "client" -> {
                HttpServer target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                target.createContext(
                        "/",
                        exchange -> {
                            byte[] body = "ok".getBytes();
                            exchange.sendResponseHeaders(200, body.length);
                            exchange.getResponseBody().write(body);
                            exchange.close();
                        });
                target.start();
                Support.answered(fetch(target.getAddress().getPort()));
                target.stop(0);
            }
            case "configured" -> {
                RouterFunctions.toHttpHandler(router());
                System.out.println("built");
            }
            case "serving" -> serve(RouterFunctions.toHttpHandler(router()));
            case "dispatcher" -> {
                try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(DispatcherConfig.class)) {
                    serve(WebHttpHandlerBuilder.applicationContext(context).build());
                }
            }
            default -> throw new IllegalArgumentException(args[0]);
        }
        Support.finish();
    }
}
