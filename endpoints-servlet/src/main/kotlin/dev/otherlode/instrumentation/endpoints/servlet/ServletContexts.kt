package dev.otherlode.instrumentation.endpoints.servlet

import dev.otherlode.bootstrap.OtherlodeEndpoints
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

internal const val MODULE_NAME = "servlet"

private val CONTEXT_TYPES = setOf("javax.servlet.ServletContext", "jakarta.servlet.ServletContext")

/** A servlet registration read from a context: its name, its class name and every mapping it holds. */
private class Registration(
    val name: String,
    val className: String?,
    val mappings: List<String>,
)

/**
 * The servlet API methods the module calls on a context, found on the API's public interfaces of
 * whichever namespace the context implements, so no servlet type has to load in the agent's loader.
 */
private class ServletApi(
    contextType: Class<*>,
) {
    private val registrations: Method
    private val classLoader: Method
    private val name: Method
    private val className: Method
    private val mappings: Method

    init {
        val context = findContextInterface(contextType) ?: throw IllegalArgumentException("${contextType.name} is no servlet context")
        val namespace = context.name.removeSuffix(".ServletContext")
        val loader = context.classLoader
        val registration = Class.forName("$namespace.ServletRegistration", false, loader)
        val base = Class.forName("$namespace.Registration", false, loader)
        registrations = context.getMethod("getServletRegistrations")
        classLoader = context.getMethod("getClassLoader")
        name = base.getMethod("getName")
        className = base.getMethod("getClassName")
        mappings = registration.getMethod("getMappings")
    }

    fun registrationsOf(context: Any): List<Registration> {
        val all = invoke(registrations, context) as Map<*, *>
        return all.values.filterNotNull().map {
            Registration(
                invoke(name, it) as String,
                invoke(className, it) as String?,
                (invoke(mappings, it) as Collection<*>).filterIsInstance<String>(),
            )
        }
    }

    fun loaderOf(context: Any): ClassLoader = (invoke(classLoader, context) as ClassLoader?) ?: context.javaClass.classLoader

    private fun invoke(
        method: Method,
        target: Any,
    ): Any? =
        try {
            method.invoke(target)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

    private fun findContextInterface(type: Class<*>): Class<*>? {
        var current: Class<*>? = type
        while (current != null) {
            for (candidate in current.interfaces) {
                if (candidate.name in CONTEXT_TYPES) return candidate
                findContextInterface(candidate)?.let { return it }
            }
            current = current.superclass
        }
        return null
    }

    companion object {
        private val byContextClass =
            object : ClassValue<ServletApi>() {
                override fun computeValue(type: Class<*>): ServletApi = ServletApi(type)
            }

        fun of(context: Any): ServletApi = byContextClass.get(context.javaClass)
    }
}

/** A mapping outside the specification's five forms. */
private class UnsupportedMapping(
    message: String,
) : RuntimeException(message)

private class Held(
    context: Any,
) : WeakReference<Any>(context) {
    val declared = HashSet<String>()
}

/**
 * The servlet contexts the module has declared, held weakly, and what it declared for each.
 *
 * A context is declared at the first counted request it receives, from its servlet registrations:
 * every mapping of every registration that is neither an owned servlet nor a container servlet. The
 * module reads the registrations of every held context again before each manifest, so a mapping
 * added since is declared within one flush interval.
 *
 * A mapping outside the specification's five forms, or a servlet class whose class file cannot be
 * read, disables the module with `ROUTE_WALK_FAILED`, and it declares nothing from that read.
 */
internal class ServletContexts(
    private val shapes: ServletShapes,
) {
    private val lock = Any()

    @Volatile
    private var held: Array<Held> = emptyArray()

    /**
     * Declares [context] the first time it is handed over, and does nothing for one already held. A
     * request that arrives while the first one declares waits for it, so it finds the endpoints.
     */
    fun declare(context: Any) {
        if (isHeld(context)) return
        synchronized(lock) {
            if (isHeld(context)) return
            val entry = Held(context)
            if (refresh(entry, context)) held = (held.filter { it.get() != null } + entry).toTypedArray()
        }
    }

    /** Reads the registrations of every held context again and declares what was added. */
    fun refreshAll() {
        synchronized(lock) {
            val kept = ArrayList<Held>()
            for (entry in held) {
                val context = entry.get() ?: continue
                if (refresh(entry, context)) kept += entry
            }
            held = kept.toTypedArray()
        }
    }

    private fun isHeld(context: Any): Boolean {
        val snapshot = held
        for (i in snapshot.indices) {
            if (snapshot[i].get() === context) return true
        }
        return false
    }

    private fun refresh(
        entry: Held,
        context: Any,
    ): Boolean {
        if (OtherlodeEndpoints.isDisabled(MODULE_NAME)) return true
        try {
            val api = ServletApi.of(context)
            val loader = api.loaderOf(context)
            val pending = mutableListOf<Triple<Registration, String, ServletShape.Counted>>()
            val skipped = mutableListOf<String>()
            for (registration in api.registrationsOf(context)) {
                for (mapping in registration.mappings) {
                    val id = declaredId(registration, mapping)
                    if (id in entry.declared) continue
                    val className =
                        registration.className ?: throw UnreadableServlet("servlet ${registration.name} has no class name")
                    when (val shape = shapes.of(className, loader)) {
                        is ServletShape.Left -> skipped += id
                        is ServletShape.Counted -> pending += Triple(registration, mapping, shape)
                    }
                }
            }
            for ((registration, mapping, _) in pending) {
                readMapping(mapping)
                    ?: throw UnsupportedMapping(
                        "servlet ${registration.name} is mapped by \"$mapping\", which is none of the five forms the servlet specification defines",
                    )
            }
            for ((registration, mapping, shape) in pending) {
                register(context, registration, mapping, shape)
                entry.declared += declaredId(registration, mapping)
            }
            entry.declared += skipped
            return true
        } catch (e: IllegalStateException) {
            return false
        } catch (t: Throwable) {
            OtherlodeEndpoints.moduleDisabled(MODULE_NAME, OtherlodeEndpoints.KIND_ROUTE_WALK_FAILED, t.toString())
            return true
        }
    }

    private fun register(
        context: Any,
        registration: Registration,
        mapping: String,
        shape: ServletShape.Counted,
    ) {
        val form = checkNotNull(readMapping(mapping))
        for (served in shape.verbs) {
            for (request in served.requests) {
                OtherlodeEndpoints.register(
                    MODULE_NAME,
                    listOf(context, registration.name, mapping, request, shape.entryClass),
                    served.verb,
                    form.template,
                    null,
                    served.handler.className,
                    served.handler.methodName,
                    served.handler.descriptor,
                    form.kind,
                )
            }
        }
    }

    private fun declaredId(
        registration: Registration,
        mapping: String,
    ): String = registration.name + "\u0000" + mapping
}
