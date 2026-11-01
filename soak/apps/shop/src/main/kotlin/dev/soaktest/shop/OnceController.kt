package dev.soaktest.shop

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class OnceController {
    @GetMapping("/once")
    fun once(): String = "once"
}
