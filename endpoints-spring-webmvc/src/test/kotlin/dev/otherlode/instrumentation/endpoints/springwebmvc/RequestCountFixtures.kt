package dev.otherlode.instrumentation.endpoints.springwebmvc

import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.handler.HandlerMappingIntrospector
import java.util.concurrent.Callable

/** A handler that completes on another thread, so a request to it is dispatched twice. */
@RestController
class AsyncController {
    @GetMapping("/async")
    fun async(): Callable<String> = Callable { "async" }
}

/** A route that answers `OPTIONS` itself, beside an ordinary one. */
@RestController
class OptionsController {
    @RequestMapping(path = ["/opt"], method = [RequestMethod.OPTIONS])
    fun options(): String = "options"
}

/**
 * Looks [uri] up the way Spring Security and CORS handling do: through a
 * [HandlerMappingIntrospector] over [context], which calls each handler mapping's `getHandler`
 * with the request wrapped in its own request class.
 */
fun introspectorLookup(
    context: WebApplicationContext,
    uri: String,
) {
    val introspector = HandlerMappingIntrospector()
    introspector.setApplicationContext(context)
    introspector.afterPropertiesSet()
    introspector.getCorsConfiguration(MockHttpServletRequest("GET", uri))
}

/**
 * Builds a `RouterFunctionMapping` from [routerFunction] with its constructor and initialises it.
 * It reaches the mapping by name so no class file here resolves it before the module installs.
 */
fun initialisedRouterFunctionMapping(routerFunction: RouterFunction<*>): Any {
    val mappingClass = Class.forName("org.springframework.web.servlet.function.support.RouterFunctionMapping")
    val mapping = mappingClass.getConstructor(RouterFunction::class.java).newInstance(routerFunction)
    mappingClass.getMethod("afterPropertiesSet").invoke(mapping)
    return mapping
}
