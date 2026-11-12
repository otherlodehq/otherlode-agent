package com.example.classsam

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer

fun handleOrder(exchange: HttpExchange) {
    exchange.sendResponseHeaders(204, -1)
    exchange.close()
}

private fun handleSecret(exchange: HttpExchange) {
    exchange.sendResponseHeaders(204, -1)
    exchange.close()
}

fun reference(): HttpHandler = HttpHandler(::handleOrder)

fun privateReference(): HttpHandler = HttpHandler(::handleSecret)

fun lambda(): HttpHandler = HttpHandler { handleOrder(it) }

fun register(server: HttpServer) {
    server.createContext("/order", ::handleOrder)
}

fun fromValue(function: (HttpExchange) -> Unit): HttpHandler = HttpHandler(function)

fun wrapped(): HttpHandler = fromValue(::handleOrder)
