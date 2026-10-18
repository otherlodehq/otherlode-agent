package dev.otherlode.instrumentation.endpoints.otelbridge

import jakarta.servlet.Filter
import jakarta.servlet.Servlet
import org.apache.catalina.Context
import org.apache.catalina.startup.Tomcat
import org.apache.tomcat.util.descriptor.web.FilterDef
import org.apache.tomcat.util.descriptor.web.FilterMap
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.context.ContextLoaderListener
import org.springframework.web.context.support.GenericWebApplicationContext
import org.springframework.web.servlet.DispatcherServlet
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import java.net.Socket
import java.nio.file.Files
import java.util.function.Supplier

@Controller
class OrdersController {
    @GetMapping("/orders")
    @ResponseBody
    fun orders(): String = "orders"
}

@Configuration(proxyBeanMethods = false)
@EnableWebMvc
class WebConfig

/** An embedded Tomcat with one web application under [contextPath]. */
class TomcatApp(
    val contextPath: String,
    configure: (Context) -> Unit,
) : AutoCloseable {
    private val tomcat = Tomcat()

    init {
        val base = Files.createTempDirectory("otel-bridge-test")
        tomcat.setBaseDir(base.toString())
        tomcat.setPort(0)
        tomcat.connector
        val context = tomcat.addContext(contextPath, base.toString())
        configure(context)
        tomcat.start()
    }

    /**
     * Sends a GET for [path] and returns the status once the server has closed the connection. The
     * server closes it after the request has finished, which is after OpenTelemetry has ended the
     * request's span, so what the span end recorded is in place by the time this returns.
     */
    fun get(path: String): Int =
        Socket("127.0.0.1", tomcat.connector.localPort).use { socket ->
            socket.soTimeout = RESPONSE_TIMEOUT_MILLIS
            socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
            val response = socket.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
            response.substringAfter("HTTP/1.1 ").take(STATUS_LENGTH).toInt()
        }

    override fun close() {
        tomcat.stop()
        tomcat.destroy()
    }

    private companion object {
        const val RESPONSE_TIMEOUT_MILLIS = 10_000
        const val STATUS_LENGTH = 3
    }
}

fun Context.servlet(
    name: String,
    servlet: Servlet,
    vararg mappings: String,
) {
    val wrapper = Tomcat.addServlet(this, name, servlet)
    wrapper.loadOnStartup = 1
    for (mapping in mappings) addServletMappingDecoded(mapping, name)
}

fun Context.filter(
    name: String,
    filter: Filter,
    pattern: String,
) {
    val definition = FilterDef()
    definition.filterName = name
    definition.filter = filter
    addFilterDef(definition)
    val map = FilterMap()
    map.filterName = name
    map.addURLPattern(pattern)
    addFilterMap(map)
}

/**
 * A Spring MVC application under [contextPath], wired as Spring Boot wires one: the dispatcher
 * servlet is a bean of a root context that a listener refreshes before the filters start.
 * [filter] is registered for every path, ahead of the dispatcher.
 */
fun springApp(
    contextPath: String,
    filter: Filter? = null,
): TomcatApp {
    val dispatcher = DispatcherServlet()
    val root = GenericWebApplicationContext()
    AnnotatedBeanDefinitionReader(root).register(WebConfig::class.java, OrdersController::class.java)
    root.registerBean("dispatcherServlet", DispatcherServlet::class.java, Supplier { dispatcher })
    val app =
        TomcatApp(contextPath) { context ->
            context.addServletContainerInitializer({ _, servletContext -> servletContext.addListener(ContextLoaderListener(root)) }, null)
            if (filter != null) context.filter("telemetry", filter, "/*")
            context.servlet("dispatcher", dispatcher, "/")
        }
    root.publishEvent(ContextRefreshedEvent(root))
    return app
}
