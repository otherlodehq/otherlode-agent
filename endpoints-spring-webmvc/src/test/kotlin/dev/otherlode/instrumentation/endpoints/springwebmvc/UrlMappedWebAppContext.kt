package dev.otherlode.instrumentation.endpoints.springwebmvc

import org.springframework.context.annotation.AnnotatedBeanDefinitionReader
import org.springframework.mock.web.MockServletContext
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.context.support.GenericWebApplicationContext
import org.springframework.web.servlet.config.annotation.DelegatingWebMvcConfiguration
import java.util.function.Supplier

fun buildUrlMappedWebApplicationContext(
    staticHandler: Any,
    legacyHandler: Any,
    fallbackHandler: Any,
    mappedRootHandler: Any? = null,
    directRootHandler: Any? = null,
    lazyRootHandler: Any? = null,
    earlierDefaultHandler: Any? = null,
): WebApplicationContext {
    val context = GenericWebApplicationContext()
    context.servletContext = MockServletContext()
    AnnotatedBeanDefinitionReader(context).register(DelegatingWebMvcConfiguration::class.java)
    // registerBean ends in a vararg customizer, so a trailing lambda would bind there instead of to the Supplier.
    context.registerBean("testController", TestController::class.java, Supplier { TestController() })

    // SimpleUrlHandlerMapping is reached by name so no class file here loads it before install runs.
    @Suppress("UNCHECKED_CAST")
    val mappingClass = Class.forName("org.springframework.web.servlet.handler.SimpleUrlHandlerMapping") as Class<Any>
    val constructor = mappingClass.getDeclaredConstructor()
    val setUrlMap = mappingClass.getMethod("setUrlMap", Map::class.java)
    val setRootHandler = mappingClass.getMethod("setRootHandler", Any::class.java)
    val setLazyInitHandlers = mappingClass.getMethod("setLazyInitHandlers", Boolean::class.javaPrimitiveType)
    val setDefaultHandler = mappingClass.getMethod("setDefaultHandler", Any::class.java)
    val setOrder = mappingClass.getMethod("setOrder", Int::class.javaPrimitiveType)
    if (lazyRootHandler != null) context.registerBean("lazyRoot", Any::class.java, Supplier { lazyRootHandler })
    if (earlierDefaultHandler != null) {
        context.registerBean(
            "earlierUrlHandlerMapping",
            mappingClass,
            Supplier {
                val mapping = constructor.newInstance()
                setDefaultHandler.invoke(mapping, earlierDefaultHandler)
                setOrder.invoke(mapping, -1)
                mapping
            },
        )
    }
    context.registerBean(
        "urlHandlerMapping",
        mappingClass,
        Supplier {
            val mapping = constructor.newInstance()
            val urlMap = mutableMapOf("/static/**" to staticHandler, "/legacy/old" to legacyHandler, "/*" to fallbackHandler)
            if (mappedRootHandler != null) urlMap["/"] = mappedRootHandler
            if (lazyRootHandler != null) {
                setLazyInitHandlers.invoke(mapping, true)
                urlMap["/"] = "lazyRoot"
            }
            setUrlMap.invoke(mapping, urlMap)
            if (directRootHandler != null) setRootHandler.invoke(mapping, directRootHandler)
            mapping
        },
    )
    context.refresh()
    return context
}
