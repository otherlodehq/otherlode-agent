package dev.otherlode.instrumentation

/**
 * The annotations a framework calls a method by. A method that carries one has an outside caller
 * of kind `CALLBACK_ANNOTATION` (ADR 0064).
 *
 * The names were checked against current releases of each framework on 2026-10-06. Every one has
 * runtime retention. Adding a name changes no wire field.
 *
 * Names are internal, with slashes, and a nested type keeps its `$`. [onMethod] holds the
 * annotations that count on a method. [onParameter] holds the ones that count on a parameter, which
 * `@Observes` and `@ObservesAsync` are the only ones to use. An annotation in one set does not count
 * in the other place: `@ModelAttribute` on a parameter marks nothing.
 *
 * Left out on purpose: Spring's `@HttpExchange` family, which marks client interfaces; servlet
 * annotations, which sit on classes; `@Reflective`, which `@Async` also carries; and any rule that
 * takes every out-of-scope runtime annotation.
 */
internal object CallbackAnnotations {
    /** Annotations that count when they sit on the method. */
    val onMethod: Set<String> =
        buildSet {
            // Spring Framework: events, scheduling, beans, web and messaging.
            full(
                "org.springframework.context.event.EventListener",
                "org.springframework.transaction.event.TransactionalEventListener",
                "org.springframework.scheduling.annotation.Scheduled",
                "org.springframework.scheduling.annotation.Schedules",
                "org.springframework.context.annotation.Bean",
                "org.springframework.messaging.handler.annotation.MessageMapping",
                "org.springframework.messaging.handler.annotation.MessageExceptionHandler",
                "org.springframework.messaging.simp.annotation.SubscribeMapping",
                "org.springframework.jms.annotation.JmsListener",
                "org.springframework.jms.annotation.JmsListeners",
            )
            inPackage(
                "org.springframework.web.bind.annotation",
                "RequestMapping",
                "GetMapping",
                "PostMapping",
                "PutMapping",
                "DeleteMapping",
                "PatchMapping",
                "ExceptionHandler",
                "ModelAttribute",
                "InitBinder",
            )
            // Spring Kafka and Spring AMQP.
            inPackage(
                "org.springframework.kafka.annotation",
                "KafkaListener",
                "KafkaListeners",
                "KafkaHandler",
            )
            inPackage(
                "org.springframework.amqp.rabbit.annotation",
                "RabbitListener",
                "RabbitListeners",
                "RabbitHandler",
            )
            inPackage(
                "org.springframework.graphql.data.method.annotation",
                "SchemaMapping",
                "QueryMapping",
                "MutationMapping",
                "SubscriptionMapping",
                "BatchMapping",
                "GraphQlExceptionHandler",
            )
            inPackage(
                "org.springframework.boot.actuate.endpoint.annotation",
                "ReadOperation",
                "WriteOperation",
                "DeleteOperation",
            )
            // Lifecycle callbacks, in both namespaces.
            inBothNamespaces("annotation", "PostConstruct", "PreDestroy")
            // JAX-RS. HttpMethod is the meta-annotation on every verb, so a custom verb is found through it.
            inBothNamespaces("ws.rs", "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "Path", "HttpMethod")
            inBothNamespaces("ejb", "Schedule", "Schedules", "Timeout")
            inBothNamespaces("interceptor", "AroundInvoke", "AroundTimeout")
            inBothNamespaces("websocket", "OnMessage", "OnOpen", "OnClose", "OnError")
            inBothNamespaces(
                "persistence",
                "PrePersist",
                "PostPersist",
                "PreUpdate",
                "PostUpdate",
                "PreRemove",
                "PostRemove",
                "PostLoad",
            )
            // Injection. A constructor never counts, since constructors are left out before this list is read.
            inBothNamespaces("inject", "Inject")
            // Micronaut. HttpMethodMapping is the meta-annotation on @Get, @Post, @Error and the rest.
            full(
                "io.micronaut.scheduling.annotation.Scheduled",
                "io.micronaut.scheduling.annotation.Schedules",
                "io.micronaut.runtime.event.annotation.EventListener",
                "io.micronaut.http.annotation.HttpMethodMapping",
            )
            full(
                "io.quarkus.scheduler.Scheduled",
                "io.quarkus.scheduler.Scheduled\$Schedules",
                "io.quarkus.runtime.Startup",
            )
            full("com.google.common.eventbus.Subscribe")
        }

    /** Annotations that count when they sit on a parameter of the method. */
    val onParameter: Set<String> =
        buildSet {
            // CDI observers, in both namespaces.
            inBothNamespaces("enterprise.event", "Observes", "ObservesAsync")
        }

    /** Adds full dotted names. */
    private fun MutableSet<String>.full(vararg fullNames: String) {
        fullNames.forEach { add(it.replace('.', '/')) }
    }

    /** Adds each of [simpleNames] under the package [prefix]. */
    private fun MutableSet<String>.inPackage(
        prefix: String,
        vararg simpleNames: String,
    ) {
        simpleNames.forEach { add("${prefix.replace('.', '/')}/$it") }
    }

    /** Adds each of [simpleNames] under both `jakarta.<packageName>` and `javax.<packageName>`. */
    private fun MutableSet<String>.inBothNamespaces(
        packageName: String,
        vararg simpleNames: String,
    ) {
        for (root in listOf("jakarta", "javax")) inPackage("$root.$packageName", *simpleNames)
    }
}
