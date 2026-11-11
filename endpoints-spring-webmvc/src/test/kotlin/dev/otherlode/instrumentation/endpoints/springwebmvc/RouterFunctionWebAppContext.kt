package dev.otherlode.instrumentation.endpoints.springwebmvc

import org.springframework.context.annotation.AnnotatedBeanDefinitionReader
import org.springframework.mock.web.MockServletContext
import org.springframework.web.context.WebApplicationContext
import org.springframework.web.context.support.GenericWebApplicationContext
import org.springframework.web.servlet.config.annotation.DelegatingWebMvcConfiguration
import org.springframework.web.servlet.function.RouterFunction
import java.util.function.Supplier

fun buildFunctionalWebApplicationContext(routerFunction: RouterFunction<*>): WebApplicationContext {
    val context = GenericWebApplicationContext()
    context.servletContext = MockServletContext()
    AnnotatedBeanDefinitionReader(context).register(DelegatingWebMvcConfiguration::class.java)

    @Suppress("UNCHECKED_CAST")
    val routerFunctionClass = RouterFunction::class.java as Class<Any>
    context.registerBean("functionalRoutes", routerFunctionClass, Supplier { routerFunction })

    context.refresh()
    return context
}
