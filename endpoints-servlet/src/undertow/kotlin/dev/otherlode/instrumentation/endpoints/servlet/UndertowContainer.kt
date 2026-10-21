package dev.otherlode.instrumentation.endpoints.servlet

import io.undertow.Handlers
import io.undertow.Undertow
import io.undertow.servlet.Servlets
import io.undertow.servlet.api.DeploymentInfo
import io.undertow.servlet.api.DeploymentManager
import io.undertow.servlet.api.FilterInfo
import io.undertow.servlet.api.ServletInfo
import io.undertow.servlet.handlers.DefaultServlet
import io.undertow.servlet.util.ImmediateInstanceFactory
import jakarta.servlet.DispatcherType
import jakarta.servlet.Filter
import jakarta.servlet.Servlet
import jakarta.servlet.ServletContext
import java.net.InetSocketAddress

fun containerUnderTest(): ServletContainer = UndertowContainer()

/** An adopter's subclass of the container's default servlet. */
class MyDefaultServlet : DefaultServlet()

/** Embedded Undertow with `undertow-servlet`, in the release the suite's dependencies select. */
class UndertowContainer : ServletContainer {
    override val defaultServlets: Set<String> = setOf("io.undertow.servlet.handlers.DefaultServlet")
    override val ownServlets: Set<String> = setOf("io.undertow.servlet.handlers.DefaultServlet")

    override fun start(
        contextPath: String,
        configure: WebApp.() -> Unit,
    ): RunningApp {
        val path = contextPath.ifEmpty { "/" }
        val deployment =
            Servlets
                .deployment()
                .setClassLoader(UndertowContainer::class.java.classLoader)
                .setContextPath(path)
                .setDeploymentName("servlet-test" + System.nanoTime())
        UndertowWebApp(deployment).configure()
        val manager = Servlets.defaultContainer().addDeployment(deployment)
        manager.deploy()
        val server =
            Undertow
                .builder()
                .addHttpListener(
                    0,
                    LOOPBACK,
                ).setHandler(Handlers.path().addPrefixPath(path, manager.start()))
                .build()
        server.start()
        val port = (server.listenerInfo.single().address as InetSocketAddress).port
        return UndertowRunningApp(server, manager, port, contextPath)
    }
}

private class UndertowWebApp(
    private val deployment: DeploymentInfo,
) : WebApp {
    override fun servlet(
        name: String,
        servlet: Servlet,
        vararg mappings: String,
    ) {
        val info = ServletInfo(name, servlet.javaClass, ImmediateInstanceFactory(servlet))
        info.isAsyncSupported = true
        info.addMappings(*mappings)
        deployment.addServlet(info)
    }

    override fun filter(
        name: String,
        filter: Filter,
        pattern: String,
    ) {
        deployment.addFilter(FilterInfo(name, filter.javaClass, ImmediateInstanceFactory(filter)))
        deployment.addFilterUrlMapping(name, pattern, DispatcherType.REQUEST)
    }

    override fun errorPage(
        status: Int,
        location: String,
    ) {
        deployment.addErrorPage(Servlets.errorPage(location, status))
    }

    override fun containerDefaults() = Unit

    override fun adopterDefaultServlet(
        name: String,
        mapping: String,
    ) = servlet(name, MyDefaultServlet(), mapping)
}

private class UndertowRunningApp(
    private val server: Undertow,
    private val manager: DeploymentManager,
    override val port: Int,
    private val contextPath: String,
) : RunningApp {
    override val servletContext: ServletContext = manager.deployment.servletContext

    override fun addMappingAfterStart(
        servletName: String,
        pattern: String,
    ) {
        servletContext.getServletRegistration(servletName).addMapping(pattern)
    }

    override fun send(
        path: String,
        method: String,
    ): Int = sendHttp(port, contextPath, path, method)

    override fun close() {
        server.stop()
        manager.stop()
        manager.undeploy()
    }
}
