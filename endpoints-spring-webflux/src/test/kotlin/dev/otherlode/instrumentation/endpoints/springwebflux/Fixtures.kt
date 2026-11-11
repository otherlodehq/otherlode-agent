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

@RestController
class RootController {
    @GetMapping
    fun root(): String = "root"
}

open class BaseController {
    @GetMapping("/inherited")
    fun inherited(): String = "inherited"
}

@RestController
class InheritingController : BaseController()

@RestController
class ItemsController {
    @GetMapping("/items/{id}", "/items/**")
    fun items(): String = "items"
}

@RestController
class OptionsController {
    @RequestMapping(path = ["/opt"], method = [RequestMethod.OPTIONS])
    fun options(): String = "options"
}

class ProgrammaticHandler {
    @ResponseBody
    fun registered(): String = "registered"
}

fun urlMappedWebHandler(): WebHandler = WebHandler { Mono.empty() }

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
        // SimpleUrlHandlerMapping is reached by name so no class file here loads it before install runs.
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

fun initialisedRouterFunctionMapping(routerFunction: RouterFunction<*>): Any {
    // Reached by name so no class file here loads RouterFunctionMapping before install runs.
    val mappingClass = Class.forName("org.springframework.web.reactive.function.server.support.RouterFunctionMapping")
    val mapping = mappingClass.getConstructor(RouterFunction::class.java).newInstance(routerFunction)
    mappingClass.getMethod("afterPropertiesSet").invoke(mapping)
    return mapping
}

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

val PREFLIGHT_HEADERS = mapOf("Origin" to "http://example.com", "Access-Control-Request-Method" to "GET")

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

fun hits(registry: EndpointRegistry): Map<String, Long> {
    val endpoints = registry.endpoints().associateBy { it.endpointId }
    return registry
        .computeDeltas(maxPerBatch = 100)
        .flatMap { it.deltas }
        .associate { delta -> endpoints.getValue(delta.endpointId).let { "${it.verb} ${it.routeTemplate}" } to delta.hitsTotal }
}

fun identities(registry: EndpointRegistry): Set<String> = registry.endpoints().map { "${it.verb} ${it.routeTemplate}" }.toSet()
