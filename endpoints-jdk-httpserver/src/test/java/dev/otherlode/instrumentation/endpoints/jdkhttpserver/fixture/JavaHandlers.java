package dev.otherlode.instrumentation.endpoints.jdkhttpserver.fixture;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;

public final class JavaHandlers {
    public interface NamedHandler extends HttpHandler {
    }

    public static HttpHandler lambda() {
        return exchange -> respond(exchange);
    }

    public static HttpHandler capturingLambda(String body) {
        return exchange -> respond(exchange, body);
    }

    public static HttpHandler staticReference() {
        return JavaHandlers::handleStatically;
    }

    public HttpHandler boundReference() {
        return this::handle;
    }

    public static HttpHandler interfaceReference(HttpHandler target) {
        return target::handle;
    }

    // Only the test that spins this lambda before it installs the hook calls this method.
    public static HttpHandler lambdaSpunEarly() {
        return exchange -> respond(exchange);
    }

    public static HttpHandler namedHandlerLambda() {
        NamedHandler handler = exchange -> respond(exchange);
        return handler;
    }

    public static void register(HttpServer server, String path, HttpHandler handler) {
        server.createContext(path, handler);
    }

    public static void handleStatically(HttpExchange exchange) throws IOException {
        respond(exchange);
    }

    public void handle(HttpExchange exchange) throws IOException {
        respond(exchange);
    }

    static void respond(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(200, -1);
        exchange.close();
    }

    static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
