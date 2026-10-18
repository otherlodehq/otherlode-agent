package markers;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class Support {
    private Support() {}

    static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    static String get(int port) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/").openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        try (var in = connection.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static void answered(String body) {
        System.out.println("response: " + body);
    }

    static void finish() {
        System.out.flush();
        System.exit(0);
    }
}
