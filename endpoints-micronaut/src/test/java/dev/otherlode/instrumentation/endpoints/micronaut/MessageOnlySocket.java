package dev.otherlode.instrumentation.endpoints.micronaut;

import io.micronaut.websocket.WebSocketSession;
import io.micronaut.websocket.annotation.OnMessage;
import io.micronaut.websocket.annotation.ServerWebSocket;

@ServerWebSocket("/ws/message")
public class MessageOnlySocket {
    @OnMessage
    public void message(String message, WebSocketSession session) {
        session.sendSync(message);
    }
}
