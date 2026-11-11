package dev.otherlode.instrumentation.endpoints.springwebmvc

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.bind.annotation.RestController

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

open class BaseController {
    @GetMapping("/inherited")
    fun inherited(): String = "inherited"
}

@RestController
class InheritingController : BaseController()

// Spring's best match for /items/7 is "/items/{id}", and a HashSet of these two patterns iterates "/items/**" first.
@RestController
class ItemsController {
    @GetMapping("/items/{id}", "/items/**")
    fun items(): String = "items"
}

class ProgrammaticHandler {
    @ResponseBody
    fun registered(): String = "registered"
}
