package dev.otherlode.registry

import dev.otherlode.export.FrameworkMarker
import java.util.concurrent.ConcurrentHashMap

/**
 * One entry of the agent's fixed list of framework markers: a stable kebab-case [id] and the
 * alternative [classSets] that show a framework's routing is set up. A set matches when every class
 * in it is defined; the marker is seen when any set matches. The wording of what a marker claims
 * belongs to the server.
 */
class FrameworkMarkerDefinition(
    val id: String,
    val classSets: List<List<String>>,
)

/** The markers the agent looks for. Each is proved by a fixture in `framework-markers` before it is listed. */
object FrameworkMarkers {
    val ALL: List<FrameworkMarkerDefinition> =
        listOf(
            FrameworkMarkerDefinition("grpc-server", listOf(listOf("io.grpc.internal.ServerImpl\$ServerListenerImpl"))),
            FrameworkMarkerDefinition("armeria-server", listOf(listOf("com.linecorp.armeria.server.HttpServerPipelineConfigurator"))),
            FrameworkMarkerDefinition("undertow-routing-handler", listOf(listOf("io.undertow.server.RoutingHandler"))),
            FrameworkMarkerDefinition(
                "javalin-5",
                listOf(listOf("io.javalin.jetty.JettyServer\$start\$wsAndHttpHandler\$1", "io.javalin.http.servlet.JavalinServlet")),
            ),
            FrameworkMarkerDefinition(
                "play-server",
                listOf(
                    listOf("play.core.server.PekkoHttpServer"),
                    listOf("play.core.server.AkkaHttpServer"),
                    listOf("play.core.server.NettyServer"),
                ),
            ),
            FrameworkMarkerDefinition("helidon-webserver", listOf(listOf("io.helidon.webserver.LoomServer\$ListenerFuture"))),
            FrameworkMarkerDefinition(
                "struts2-dispatcher",
                listOf(
                    listOf("org.apache.struts2.dispatcher.ExecuteOperations"),
                    listOf("org.apache.struts2.dispatcher.ng.ExecuteOperations"),
                ),
            ),
            FrameworkMarkerDefinition(
                "grails-url-mappings",
                listOf(
                    listOf(
                        "org.grails.web.servlet.mvc.GrailsDispatcherServlet",
                        "org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext",
                    ),
                    listOf(
                        "org.grails.web.servlet.mvc.GrailsDispatcherServlet",
                        "org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext",
                    ),
                    listOf(
                        "org.grails.web.servlet.mvc.GrailsDispatcherServlet",
                        "org.springframework.boot.web.servlet.support.SpringBootServletInitializer\$WarDeploymentApplicationContextFactory",
                    ),
                ),
            ),
            FrameworkMarkerDefinition(
                "webflux-router-without-dispatcher",
                listOf(listOf("org.springframework.web.reactive.function.server.RouterFunctions\$RouterFunctionWebHandler")),
            ),
        )
}

/**
 * The framework markers seen in this run and which of them a confirmed manifest has carried.
 *
 * [observe] reads the names of classes the JVM has already defined and never resolves a class, so
 * a marker is seen only when its classes loaded without the agent's help. A marker once seen stays
 * seen: the wire has no retraction.
 */
class FrameworkMarkerRegistry(
    private val markers: List<FrameworkMarkerDefinition> = FrameworkMarkers.ALL,
) {
    private val watched: Set<String> = markers.flatMapTo(HashSet()) { marker -> marker.classSets.flatten() }
    private val seen = ConcurrentHashMap<String, Entry>()

    private class Entry(
        val marker: FrameworkMarker,
    ) {
        @Volatile
        var delivered: Boolean = false
    }

    /** The markers not yet delivered; [advanceManifest] marks exactly these as delivered. */
    class ManifestSnapshot internal constructor(
        val markers: List<FrameworkMarker>,
    )

    /**
     * Records every marker not yet seen that has a class set wholly among [loaded]'s names, stamped
     * [seenAt]. A name lookup per class and nothing else.
     */
    fun observe(
        loaded: Array<Class<*>>,
        seenAt: Long,
    ) {
        if (seen.size >= markers.size) return
        val defined = HashSet<String>()
        for (type in loaded) {
            val name = type.name
            if (name in watched) defined += name
        }
        if (defined.isEmpty()) return
        for (marker in markers) {
            if (seen.containsKey(marker.id)) continue
            val matched = marker.classSets.firstOrNull { defined.containsAll(it) } ?: continue
            seen.putIfAbsent(marker.id, Entry(FrameworkMarker(marker.id, matched, seenAt)))
        }
    }

    /** Whether some marker in the list has not been seen yet, so the loaded classes are still worth reading. */
    fun hasUnseen(): Boolean = seen.size < markers.size

    /** Every marker seen so far, in the fixed list's order. */
    fun seenMarkers(): List<FrameworkMarker> = markers.mapNotNull { seen[it.id]?.marker }

    /**
     * The markers seen and not yet delivered, or null when there are none. Nothing is marked as
     * delivered here; call [advanceManifest] with the result once the manifest carrying it is
     * confirmed.
     */
    fun computeManifestEntries(): ManifestSnapshot? {
        val due = markers.mapNotNull { seen[it.id] }.filter { !it.delivered }.map { it.marker }
        return if (due.isEmpty()) null else ManifestSnapshot(due)
    }

    /** Marks every marker [snapshot] carried as delivered. Call it only once that manifest is confirmed. */
    fun advanceManifest(snapshot: ManifestSnapshot) {
        for (marker in snapshot.markers) seen[marker.id]?.delivered = true
    }
}
