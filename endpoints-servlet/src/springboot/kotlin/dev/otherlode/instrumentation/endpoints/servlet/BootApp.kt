package dev.otherlode.instrumentation.endpoints.servlet

import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** A servlet registered beside Spring MVC. */
class BootPlainServlet : HttpServlet() {
    override fun doGet(
        req: HttpServletRequest,
        resp: HttpServletResponse,
    ) {
        resp.contentType = "text/plain"
        resp.writer.write("plain")
    }
}

/** A Spring MVC controller, counted by the Spring MVC module. */
@RestController
class BootController {
    @GetMapping("/boot/hello")
    fun hello(): String = "hello"
}

/** A Spring Boot application on embedded Tomcat, with the H2 console servlet switched on by properties. */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
class BootApp {
    @Bean
    fun bootController(): BootController = BootController()

    @Bean
    fun plainServlet(): ServletRegistrationBean<BootPlainServlet> = ServletRegistrationBean(BootPlainServlet(), "/boot/plain")
}
