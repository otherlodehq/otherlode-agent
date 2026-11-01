package dev.soaktest.shop

import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ServletConfiguration {
    @Bean
    fun healthLegacyServlet(): ServletRegistrationBean<HealthLegacyServlet> =
        ServletRegistrationBean(HealthLegacyServlet(), "/health-legacy")
}
