package dev.otherlode.instrumentation.endpoints.servlet

import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import java.util.Collections
import java.util.WeakHashMap

/** Servlets a framework module counts behind, matched by class hierarchy. */
internal val OWNED_SERVLETS: Set<String> =
    setOf(
        "org.springframework.web.servlet.DispatcherServlet",
        "org.glassfish.jersey.servlet.ServletContainer",
        "org.jboss.resteasy.plugins.server.servlet.HttpServletDispatcher",
        "org.jboss.resteasy.plugins.server.servlet.HttpServlet30Dispatcher",
        "org.apache.cxf.transport.servlet.CXFServlet",
        "org.apache.cxf.jaxrs.servlet.CXFNonSpringJaxrsServlet",
        "io.micronaut.servlet.engine.DefaultMicronautServlet",
        "io.ktor.server.servlet.ServletApplicationEngine",
        "io.ktor.server.servlet.jakarta.ServletApplicationEngine",
        "io.javalin.http.servlet.JavalinServlet",
    )

private val JETTY_EE = listOf("ee8", "ee9", "ee10", "ee11")

/** A container's own servlets, left out by exact class name: a subclass is adopter code. */
internal val CONTAINER_SERVLETS: Set<String> =
    buildSet {
        add("org.apache.catalina.servlets.DefaultServlet")
        add("org.apache.jasper.servlet.JspServlet")
        add("org.eclipse.jetty.servlet.DefaultServlet")
        add("org.eclipse.jetty.servlet.NoJspServlet")
        add("org.eclipse.jetty.jsp.JettyJspServlet")
        add("org.eclipse.jetty.servlet.ServletHandler\$Default404Servlet")
        for (ee in JETTY_EE) {
            add("org.eclipse.jetty.$ee.servlet.DefaultServlet")
            add("org.eclipse.jetty.$ee.servlet.NoJspServlet")
            add("org.eclipse.jetty.$ee.jsp.JettyJspServlet")
            add("org.eclipse.jetty.$ee.servlet.ServletHandler\$Default404Servlet")
        }
        add("io.undertow.servlet.handlers.DefaultServlet")
        add("io.quarkus.undertow.runtime.QuarkusErrorServlet")
    }

/** The method a servlet's request is handed to, as its class file declares it. */
internal class Handler(
    val className: String,
    val methodName: String,
    val descriptor: String,
)

/**
 * One verb a counted servlet serves: [verb], the request methods that count against it ([requests],
 * which holds `HEAD` as well for a `GET` the class does not split off) and the [handler] that runs.
 */
internal class ServedVerb(
    val verb: String,
    val requests: List<String>,
    val handler: Handler,
)

/** What the module does with a servlet class, decided from its class files. */
internal sealed interface ServletShape {
    /** A framework module's servlet, or a container's own: no endpoint here. */
    data object Left : ServletShape

    /**
     * A servlet this module counts. [entryClass] is the most derived class that declares the public
     * `service(ServletRequest, ServletResponse)`, which is the frame the advice counts at.
     */
    class Counted(
        val entryClass: String,
        val verbs: List<ServedVerb>,
    ) : ServletShape
}

/** A servlet class whose class file cannot be read, so its verbs and owner cannot be known. */
internal class UnreadableServlet(
    message: String,
) : RuntimeException(message)

private class MethodInfo(
    val name: String,
    val descriptor: String,
    val access: Int,
) {
    val concrete: Boolean get() = access and Opcodes.ACC_ABSTRACT == 0
}

private class ClassInfo(
    val name: String,
    val superName: String?,
    val methods: List<MethodInfo>,
)

private val DO_METHODS = linkedMapOf("doGet" to "GET", "doPost" to "POST", "doPut" to "PUT", "doDelete" to "DELETE")
private val OWN_VERB_METHODS = linkedMapOf("doHead" to "HEAD", "doOptions" to "OPTIONS", "doTrace" to "TRACE")
private val NAMESPACES = listOf("javax", "jakarta")

/**
 * Reads what a servlet class serves from the class files of its hierarchy, loading and reflecting
 * on nothing, and holds the answer per class loader.
 *
 * A servlet whose hierarchy reaches an owned servlet, or that is a container servlet by exact class
 * name, is [ServletShape.Left]. An `HttpServlet` serves the verbs of the `do*` methods its classes
 * below `HttpServlet` declare, and every verb (`*`) when one of them overrides `service`. A servlet
 * that is not an `HttpServlet` serves every verb.
 */
internal class ServletShapes {
    private val byLoader = Collections.synchronizedMap(WeakHashMap<ClassLoader, MutableMap<String, ServletShape>>())

    /** The shape of [className] as [loader] sees it. Throws [UnreadableServlet] when a class file is missing. */
    fun of(
        className: String,
        loader: ClassLoader,
    ): ServletShape {
        val cache = byLoader.getOrPut(loader) { Collections.synchronizedMap(HashMap()) }
        cache[className]?.let { return it }
        return read(className, loader).also { cache[className] = it }
    }

    private fun read(
        className: String,
        loader: ClassLoader,
    ): ServletShape {
        if (className in CONTAINER_SERVLETS) return ServletShape.Left
        val chain = ArrayList<ClassInfo>()
        var current: String? = className
        while (current != null && !isServletApi(current) && current != "java.lang.Object") {
            if (current in OWNED_SERVLETS) return ServletShape.Left
            val info =
                readClass(loader, current) ?: throw UnreadableServlet("the class file of $current (servlet $className) cannot be read")
            chain += info
            current = info.superName
        }
        val root = current.orEmpty()
        val namespace = NAMESPACES.firstOrNull { root.startsWith("$it.servlet.") }
        return if (namespace != null && root == "$namespace.servlet.http.HttpServlet") {
            httpServletShape(namespace, chain, loader)
        } else {
            genericShape(className, chain)
        }
    }

    private fun httpServletShape(
        namespace: String,
        chain: List<ClassInfo>,
        loader: ClassLoader,
    ): ServletShape {
        val httpArguments = "(L$namespace/servlet/http/HttpServletRequest;L$namespace/servlet/http/HttpServletResponse;)V"
        val serviceArguments = "(L$namespace/servlet/ServletRequest;L$namespace/servlet/ServletResponse;)V"
        val entryClass =
            chain.firstOrNull { it.declares("service", serviceArguments, publicOnly = true) }?.dotted
                ?: "$namespace.servlet.http.HttpServlet"

        val overridingService = chain.firstOrNull { it.declares("service", httpArguments) || it.declares("service", serviceArguments) }
        if (overridingService != null) {
            val descriptor = if (overridingService.declares("service", httpArguments)) httpArguments else serviceArguments
            val handler = Handler(overridingService.dotted, "service", descriptor)
            return ServletShape.Counted(entryClass, listOf(ServedVerb("*", listOf("*"), handler)))
        }

        fun handlerFor(method: String): Handler? =
            chain.firstOrNull { it.declares(method, httpArguments) }?.let { Handler(it.dotted, method, httpArguments) }

        val verbs = ArrayList<ServedVerb>()
        val head = handlerFor("doHead")
        for ((method, verb) in DO_METHODS) {
            val handler = handlerFor(method) ?: continue
            verbs += ServedVerb(verb, if (verb == "GET" && head == null) listOf("GET", "HEAD") else listOf(verb), handler)
        }
        for ((method, verb) in OWN_VERB_METHODS) {
            val handler = handlerFor(method) ?: continue
            verbs += ServedVerb(verb, listOf(verb), handler)
        }
        val patch = handlerFor("doPatch")
        if (patch != null && apiDeclaresDoPatch(loader, namespace)) verbs += ServedVerb("PATCH", listOf("PATCH"), patch)
        return ServletShape.Counted(entryClass, verbs)
    }

    private fun genericShape(
        className: String,
        chain: List<ClassInfo>,
    ): ServletShape {
        val servicePattern = Regex("\\(L(javax|jakarta)/servlet/ServletRequest;L(javax|jakarta)/servlet/ServletResponse;\\)V")
        for (info in chain) {
            val service =
                info.methods.firstOrNull { it.name == "service" && it.concrete && servicePattern.matches(it.descriptor) } ?: continue
            val handler = Handler(info.dotted, "service", service.descriptor)
            val entry = if (service.access and Opcodes.ACC_PUBLIC != 0) info.dotted else continue
            return ServletShape.Counted(entry, listOf(ServedVerb("*", listOf("*"), handler)))
        }
        throw UnreadableServlet("servlet $className declares no service method its class files show")
    }

    private val patchByLoader = Collections.synchronizedMap(WeakHashMap<ClassLoader, MutableMap<String, Boolean>>())

    private fun apiDeclaresDoPatch(
        loader: ClassLoader,
        namespace: String,
    ): Boolean =
        patchByLoader
            .getOrPut(loader) { Collections.synchronizedMap(HashMap()) }
            .getOrPut(namespace) {
                readClass(loader, "$namespace.servlet.http.HttpServlet")?.methods?.any { it.name == "doPatch" } ?: false
            }

    private fun isServletApi(name: String): Boolean = NAMESPACES.any { name.startsWith("$it.servlet.") }

    private val ClassInfo.dotted: String get() = name.replace('/', '.')

    private fun ClassInfo.declares(
        name: String,
        descriptor: String,
        publicOnly: Boolean = false,
    ): Boolean =
        methods.any {
            it.name == name && it.descriptor == descriptor && it.concrete && (!publicOnly || it.access and Opcodes.ACC_PUBLIC != 0)
        }

    private fun readClass(
        loader: ClassLoader,
        className: String,
    ): ClassInfo? {
        val stream = loader.getResourceAsStream(className.replace('.', '/') + ".class") ?: return null
        return stream.use { input ->
            val methods = ArrayList<MethodInfo>()
            val reader = ClassReader(input)
            reader.accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitMethod(
                        access: Int,
                        name: String,
                        descriptor: String,
                        signature: String?,
                        exceptions: Array<out String>?,
                    ): MethodVisitor? {
                        if (access and Opcodes.ACC_SYNTHETIC == 0) methods += MethodInfo(name, descriptor, access)
                        return null
                    }
                },
                ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
            )
            ClassInfo(reader.className.replace('/', '.'), reader.superName?.replace('/', '.'), methods)
        }
    }
}
