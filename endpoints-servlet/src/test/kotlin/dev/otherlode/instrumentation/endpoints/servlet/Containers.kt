package dev.otherlode.instrumentation.endpoints.servlet

import jakarta.servlet.Filter
import jakarta.servlet.Servlet
import jakarta.servlet.ServletContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

const val LOOPBACK = "127.0.0.1"

private val client = HttpClient.newHttpClient()

fun sendHttp(
    port: Int,
    contextPath: String,
    path: String,
    method: String,
): Int {
    val request =
        HttpRequest
            .newBuilder(URI.create("http://$LOOPBACK:$port$contextPath$path"))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build()
    return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
}

interface WebApp {
    fun servlet(
        name: String,
        servlet: Servlet,
        vararg mappings: String,
    )

    fun filter(
        name: String,
        filter: Filter,
        pattern: String,
    )

    fun errorPage(
        status: Int,
        location: String,
    )

    fun containerDefaults()

    fun adopterDefaultServlet(
        name: String,
        mapping: String,
    )
}

interface RunningApp : AutoCloseable {
    val servletContext: ServletContext

    val port: Int

    fun addMappingAfterStart(
        servletName: String,
        pattern: String,
    )

    fun send(
        path: String,
        method: String = "GET",
    ): Int
}

interface ServletContainer {
    val servesPatch: Boolean
        get() = System.getProperty("otherlode.servlet.patch") == "true"

    val defaultServlets: Set<String>

    val ownServlets: Set<String>
        get() = emptySet()

    fun start(
        contextPath: String,
        configure: WebApp.() -> Unit,
    ): RunningApp
}
