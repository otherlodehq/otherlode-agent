package com.example.demo

/** Fixed ports shared by the demo server, demo client, and stub collector processes. */
object DemoPorts {
    /** Only the port the stub collector binds when it is run by hand with no argument. */
    const val COLLECTOR_PORT = 4319
    const val SERVER_PORT = 8085
    const val SPRING_SERVER_PORT = 8090
}
