package dev.otherlode.instrumentation.endpoints.springwebmvc

import org.springframework.web.HttpRequestHandler
import java.io.PrintWriter
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

fun urlMappedTestHandler(body: String): Any {
    val handlerInterface = HttpRequestHandler::class.java
    val invocationHandler =
        InvocationHandler { proxy, method, args ->
            when (method.name) {
                "handleRequest" -> {
                    val response = args[1]
                    val writer = response.javaClass.getMethod("getWriter").invoke(response) as PrintWriter
                    writer.write(body)
                    null
                }

                "hashCode" -> {
                    System.identityHashCode(proxy)
                }

                "equals" -> {
                    proxy === args[0]
                }

                "toString" -> {
                    "UrlMappedTestHandler($body)"
                }

                else -> {
                    null
                }
            }
        }
    return Proxy.newProxyInstance(handlerInterface.classLoader, arrayOf(handlerInterface), invocationHandler)
}
