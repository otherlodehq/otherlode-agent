package dev.otherlode.instrumentation.endpoints.springwebflux

import dev.otherlode.instrumentation.endpoints.EndpointInstrumentation
import dev.otherlode.registry.EndpointRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader
import org.springframework.context.support.GenericApplicationContext
import org.springframework.http.HttpMethod
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.config.DelegatingWebFluxConfiguration
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.server.WebHandler
import reactor.core.publisher.Mono
import java.util.function.Supplier
import kotlin.test.assertTrue

/**
 * Fixture controller covering four kinds of annotated mapping: a single-verb path variable, a
 * single-verb path, one mapping shared by two paths and two verbs, and a mapping with no verb.
 */
@RestController
class TestController {
    @GetMapping("/checkout/{id}")
    fun checkout(
        @PathVariable("id") id: String,
    ): String = "checkout $id"

    @PostMapping("/promo")
    fun promo(): String = "promo"

    @RequestMapping(path = ["/a", "/b"], method = [RequestMethod.GET, RequestMethod.POST])
    fun ab(): String = "ab"

    @RequestMapping("/any")
    fun any(): String = "any"
}

/** A mapping with no path of its own, which Spring holds as the empty pattern. */
@RestController
class RootController {
    @GetMapping
    fun root(): String = "root"
}

/** Declares a mapping that [InheritingController] inherits without overriding. */
open class BaseController {
    @GetMapping("/inherited")
    fun inherited(): String = "inherited"
}

/** Serves [BaseController]'s mapping, whose method and probe live on the base class. */
@RestController
class InheritingController : BaseController()

/** One mapping with two patterns that both match `/items/7`; the best match is `/items/{id}`. */
@RestController
class ItemsController {
    @GetMapping("/items/{id}", "/items/**")
    fun items(): String = "items"
}

/** A route that answers `OPTIONS` itself. */
@RestController
class OptionsController {
    @RequestMapping(path = ["/opt"], method = [RequestMethod.OPTIONS])
    fun options(): String = "options"
}

/** A handler registered in code through `registerMapping`, the way Spring Boot Actuator registers each operation. */
class ProgrammaticHandler {
    @ResponseBody
    fun registered(): String = "registered"
}

/** A URL-mapped handler that answers with an empty body. */
fun urlMappedWebHandler(): WebHandler = WebHandler { Mono.empty() }

/**
 * Builds and refreshes a WebFlux application context holding [controllers], the router function
 * beans in [routerFunctions] and, when [urlMap] is not empty, a `SimpleUrlHandlerMapping` over it.
 *
 * `SimpleUrlHandlerMapping` and `RouterFunctionMapping` are never named as a type here, since a
 * reference that resolves before the module installs would load them unwoven. The URL mapping is
 * built by name, through reflection.
 */
fun buildWebFluxContext(
    controllers: List<Class<*>> = emptyList(),
    routerFunctions: List<RouterFunction<*>> = emptyList(),
    urlMap: Map<String, Any> = emptyMap(),
): GenericApplicationContext {
    val context = GenericApplicationContext()
    AnnotatedBeanDefinitionReader(context).register(DelegatingWebFluxConfiguration::class.java)
    for (controller in controllers) {
        context.registerBean(controller.name, controller as Class<Any>, Supplier { controller.getDeclaredConstructor().newInstance() })
    }
    routerFunctions.forEachIndexed { index, routes ->
        @Suppress("UNCHECKED_CAST")
        val routerFunctionClass = RouterFunction::class.java as Class<Any>
        context.registerBean("functionalRoutes$index", routerFunctionClass, Supplier { routes })
    }
    if (urlMap.isNotEmpty()) {
        @Suppress("UNCHECKED_CAST")
        val mappingClass = Class.forName("org.springframework.web.reactive.handler.SimpleUrlHandlerMapping") as Class<Any>
        val setUrlMap = mappingClass.getMethod("setUrlMap", Map::class.java)
        val setOrder = mappingClass.getMethod("setOrder", Int::class.javaPrimitiveType)
        context.registerBean(
            "urlHandlerMapping",
            mappingClass,
            Supplier {
                val mapping = mappingClass.getDeclaredConstructor().newInstance()
                setUrlMap.invoke(mapping, urlMap)
                setOrder.invoke(mapping, 2)
                mapping
            },
        )
    }
    context.refresh()
    return context
}

/** Builds a `RouterFunctionMapping` from [routerFunction] with its constructor and initialises it, reaching it by name. */
fun initialisedRouterFunctionMapping(routerFunction: RouterFunction<*>): Any {
    val mappingClass = Class.forName("org.springframework.web.reactive.function.server.support.RouterFunctionMapping")
    val mapping = mappingClass.getConstructor(RouterFunction::class.java).newInstance(routerFunction)
    mappingClass.getMethod("afterPropertiesSet").invoke(mapping)
    return mapping
}

/** Sends one request through a real `DispatcherHandler` and returns the status code. */
fun WebTestClient.send(
    method: HttpMethod,
    uri: String,
    headers: Map<String, String> = emptyMap(),
): Int {
    val result =
        method(method)
            .uri(uri)
            .headers { target -> headers.forEach { (name, value) -> target.add(name, value) } }
            .exchange()
            .expectBody()
            .returnResult()
    return result.status.value()
}

/** The headers of a CORS preflight request. */
val PREFLIGHT_HEADERS = mapOf("Origin" to "http://example.com", "Access-Control-Request-Method" to "GET")

/**
 * Runs [block] with the Spring WebFlux module installed on a registry of its own, then asserts
 * the module never switched itself off.
 */
fun <T> withModule(block: (EndpointRegistry) -> T): T {
    val instrumentation = ByteBuddyAgent.install()
    val registry = EndpointRegistry()
    val endpointInstrumentation = EndpointInstrumentation(registry, listOf(SpringWebFluxModule()))
    val transformer = endpointInstrumentation.install(instrumentation)
    try {
        val result = block(registry)
        assertTrue(registry.disabledModules().isEmpty(), "disabled: ${registry.disabledModules()}")
        return result
    } finally {
        endpointInstrumentation.uninstall(instrumentation, transformer)
    }
}

/** The hit count of every endpoint that has one, keyed `"<verb> <template>"`. */
fun hits(registry: EndpointRegistry): Map<String, Long> {
    val endpoints = registry.endpoints().associateBy { it.endpointId }
    return registry
        .computeDeltas(maxPerBatch = 100)
        .flatMap { it.deltas }
        .associate { delta -> endpoints.getValue(delta.endpointId).let { "${it.verb} ${it.routeTemplate}" } to delta.hitsTotal }
}

/** Every declared or discovered endpoint, as `"<verb> <template>"`. */
fun identities(registry: EndpointRegistry): Set<String> = registry.endpoints().map { "${it.verb} ${it.routeTemplate}" }.toSet()
