package dev.soaktest.shop

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class BrokenController {
    @GetMapping("/broken")
    fun broken(): String = BrokenConfig.value()
}
