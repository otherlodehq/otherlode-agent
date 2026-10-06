package com.example.target.outsidecaller;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

/** Shaped like the demo's promo handler: an {@code HttpHandler} named by its own class. */
public class ServerHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) {}
}
