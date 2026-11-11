package dev.otherlode.instrumentation.endpoints.springwebmvc

import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.handler.HandlerMappingIntrospector
import org.springframework.web.util.ServletRequestPathUtils
import java.util.concurrent.Callable

@RestController
class AsyncController {
    @GetMapping("/async")
    fun async(): Callable<String> = Callable { "async" }
}

@RestController
class OptionsController {
    @RequestMapping(path = ["/opt"], method = [RequestMethod.OPTIONS])
    fun options(): String = "options"
}

fun introspectorLookup(
    context: WebApplicationContext,
    uri: String,
) {
    val introspector = HandlerMappingIntrospector()
    introspector.setApplicationContext(context)
    introspector.afterPropertiesSet()
    introspector.getCorsConfiguration(MockHttpServletRequest("GET", uri))
}

fun lookupOutsideDispatcher(
    context: WebApplicationContext,
    uri: String,
) {
    for (mapping in context.getBeansOfType(HandlerMapping::class.java).values) {
        val request = MockHttpServletRequest("GET", uri)
        ServletRequestPathUtils.parseAndCache(request)
        runCatching { mapping.getHandler(request) }
    }
}

fun initialisedRouterFunctionMapping(routerFunction: RouterFunction<*>): Any {
    // Reached by name so no class file here loads RouterFunctionMapping before install runs.
    val mappingClass = Class.forName("org.springframework.web.servlet.function.support.RouterFunctionMapping")
    val mapping = mappingClass.getConstructor(RouterFunction::class.java).newInstance(routerFunction)
    mappingClass.getMethod("afterPropertiesSet").invoke(mapping)
    return mapping
}
