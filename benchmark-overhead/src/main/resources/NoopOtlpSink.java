import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;

/**
 * A no-op OTLP receiver for the overhead benchmark. Run as {@code java NoopOtlpSink.java <port>}.
 *
 * <p>It reads and discards every POST, counts it against its path with its byte length, and answers
 * it 200 with an empty {@code application/x-protobuf} body.
 * {@code GET /counts} answers one {@code <path> <requests> <bytes>} line per path. It serves nothing
 * else and keeps no request body.
 */
public final class NoopOtlpSink {
    private static final Map<String, LongAdder[]> COUNTS = new ConcurrentHashMap<>();

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: java NoopOtlpSink.java <port>");
            System.exit(2);
        }
        HttpServer server = HttpServer.create(new InetSocketAddress(Integer.parseInt(args[0])), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", NoopOtlpSink::handle);
        server.start();
    }

    private static void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getRawPath();
            if (method.equals("POST")) {
                long bytes;
                try (var body = exchange.getRequestBody()) {
                    bytes = body.transferTo(OutputStream.nullOutputStream());
                }
                LongAdder[] count = COUNTS.computeIfAbsent(path, p -> new LongAdder[] {new LongAdder(), new LongAdder()});
                count[0].increment();
                count[1].add(bytes);
                exchange.getResponseHeaders().set("Content-Type", "application/x-protobuf");
                exchange.sendResponseHeaders(200, -1);
            } else if (method.equals("GET") && path.equals("/counts")) {
                byte[] body = counts().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
        }
    }

    private static String counts() {
        StringBuilder out = new StringBuilder();
        new TreeMap<>(COUNTS).forEach((path, count) ->
                out.append(path).append(' ').append(count[0].sum()).append(' ').append(count[1].sum()).append('\n'));
        return out.toString();
    }
}
