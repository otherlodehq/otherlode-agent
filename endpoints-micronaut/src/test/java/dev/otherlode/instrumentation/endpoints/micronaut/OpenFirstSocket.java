package dev.otherlode.instrumentation.endpoints.micronaut;

import io.micronaut.websocket.WebSocketSession;
import io.micronaut.websocket.annotation.OnMessage;
import io.micronaut.websocket.annotation.OnOpen;
import io.micronaut.websocket.annotation.ServerWebSocket;

@ServerWebSocket("/ws/open")
public class OpenFirstSocket {
    @OnOpen
    public void opened(WebSocketSession session) {
        session.sendSync("opened");
    }

    @OnMessage
    public void message(String message, WebSocketSession session) {
        session.sendSync(message);
    }
}
