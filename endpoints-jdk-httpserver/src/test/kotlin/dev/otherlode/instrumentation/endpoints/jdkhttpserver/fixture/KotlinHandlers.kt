package dev.otherlode.instrumentation.endpoints.jdkhttpserver.fixture

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer

fun kotlinLambda(): HttpHandler = HttpHandler { exchange -> respondFromKotlin(exchange) }

fun registerKotlinReference(
    server: HttpServer,
    path: String,
) {
    server.createContext(path, ::handleFromKotlin)
}

fun handleFromKotlin(exchange: HttpExchange) = respondFromKotlin(exchange)

private fun respondFromKotlin(exchange: HttpExchange) {
    exchange.sendResponseHeaders(200, -1)
    exchange.close()
}
