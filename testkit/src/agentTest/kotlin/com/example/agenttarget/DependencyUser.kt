package com.example.agenttarget

import com.example.fixture.used.Greeter

class DependencyUser {
    fun greet(): String = Greeter.greet("otherlode")
}
