package dev.otherlode.instrumentation.endpoints.micronaut3

import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.PathVariable

@Controller("/kt")
class KotlinController {
    @Get("/hello")
    fun hello(): String = "hello"

    @Get("/susp/{id}")
    suspend fun susp(
        @PathVariable id: String,
        n: Int,
    ): String = id

    @Get("/susp-unit")
    suspend fun suspUnit() {
    }
}
