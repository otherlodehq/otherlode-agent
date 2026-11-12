package com.example.demo.spring

import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

/** Exits the process directly. */
@RestController
class ShutdownController {
    @PostMapping("/__shutdown")
    fun shutdown(): String {
        Thread { System.exit(0) }.start()
        return "shutting down"
    }
}
