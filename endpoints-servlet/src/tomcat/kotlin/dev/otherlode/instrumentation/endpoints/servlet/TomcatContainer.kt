package dev.otherlode.instrumentation.endpoints.servlet

import jakarta.servlet.Filter
import jakarta.servlet.Servlet
import jakarta.servlet.ServletContext
import org.apache.catalina.Context
import org.apache.catalina.servlets.DefaultServlet
import org.apache.catalina.startup.Tomcat
import org.apache.tomcat.util.descriptor.web.ErrorPage
import org.apache.tomcat.util.descriptor.web.FilterDef
import org.apache.tomcat.util.descriptor.web.FilterMap
import java.nio.file.Files

fun containerUnderTest(): ServletContainer = TomcatContainer()

/** An adopter's subclass of the container's default servlet. */
class MyDefaultServlet : DefaultServlet()

/** Embedded Tomcat, in the namespace and release the suite's dependencies select. */
class TomcatContainer : ServletContainer {
    override val defaultServlets: Set<String> = setOf("org.apache.catalina.servlets.DefaultServlet", "org.apache.jasper.servlet.JspServlet")

    override fun start(
        contextPath: String,
        configure: WebApp.() -> Unit,
    ): RunningApp {
        val base = Files.createTempDirectory("servlet-test")
        val tomcat = Tomcat()
        tomcat.setBaseDir(base.toString())
        tomcat.setPort(0)
        tomcat.connector
        val context = tomcat.addContext(contextPath, base.toString())
        TomcatWebApp(context).configure()
        tomcat.start()
        return TomcatRunningApp(tomcat, context, tomcat.connector.localPort, contextPath)
    }
}

private class TomcatWebApp(
    private val context: Context,
) : WebApp {
    override fun servlet(
        name: String,
        servlet: Servlet,
        vararg mappings: String,
    ) {
        Tomcat.addServlet(context, name, servlet).isAsyncSupported = true
        for (mapping in mappings) context.addServletMappingDecoded(mapping, name)
    }

    override fun filter(
        name: String,
        filter: Filter,
        pattern: String,
    ) {
        val definition = FilterDef()
        definition.filterName = name
        definition.filter = filter
        context.addFilterDef(definition)
        val map = FilterMap()
        map.filterName = name
        map.addURLPattern(pattern)
        context.addFilterMap(map)
    }

    override fun errorPage(
        status: Int,
        location: String,
    ) {
        val page = ErrorPage()
        page.errorCode = status
        page.location = location
        context.addErrorPage(page)
    }

    override fun containerDefaults() {
        Tomcat.initWebappDefaults(context)
    }

    override fun adopterDefaultServlet(
        name: String,
        mapping: String,
    ) = servlet(name, MyDefaultServlet(), mapping)
}

private class TomcatRunningApp(
    private val tomcat: Tomcat,
    context: Context,
    private val port: Int,
    private val contextPath: String,
) : RunningApp {
    private val tomcatContext = context
    override val servletContext: ServletContext = context.servletContext

    override fun addMappingAfterStart(
        servletName: String,
        pattern: String,
    ) = tomcatContext.addServletMappingDecoded(pattern, servletName)

    override fun send(
        path: String,
        method: String,
    ): Int = sendHttp(port, contextPath, path, method)

    override fun close() {
        tomcat.stop()
        tomcat.destroy()
    }
}
