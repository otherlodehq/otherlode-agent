package dev.otherlode.instrumentation

/** How a method relates to the supertype method it overrides, which decides whether an annotation passes down. */
internal enum class Relation {
    /** The supertype is a class. */
    SUPERCLASS,

    /** The supertype is an interface and its method has a body. */
    INTERFACE_DEFAULT,

    /** The supertype is an interface and its method is abstract. */
    INTERFACE_ABSTRACT,
}

/**
 * The supertype methods an annotation passes down from, as the framework that reads it behaves
 * (ADR 0069). A rule holds the [Relation]s it allows, so a rule that allows other combinations is
 * one more entry.
 */
internal enum class Inheritance(
    val relations: Set<Relation>,
) {
    /** The framework finds the annotation on a superclass method and on any interface method. */
    FROM_INTERFACES_AND_SUPERCLASSES(Relation.entries.toSet()),

    /** The framework finds the annotation on a superclass method and on an interface default method, but not an abstract one. */
    FROM_SUPERCLASSES_AND_DEFAULT_METHODS(setOf(Relation.SUPERCLASS, Relation.INTERFACE_DEFAULT)),

    /** The framework reads the annotation on the method that runs and nowhere else. */
    NEVER(emptySet()),
}

/**
 * The annotations a framework calls a method by. A method that carries one has an outside caller
 * of kind `CALLBACK_ANNOTATION` (ADR 0064), and a method that overrides one that carries it has the
 * same where its [Inheritance] rule says so (ADR 0069).
 *
 * The names were checked against current releases of each framework on 2026-10-06. Every one has
 * runtime retention. Adding a name changes no wire field, and needs the rule its framework follows.
 *
 * Names are internal, with slashes, and a nested type keeps its `$`. [inheritance] holds the
 * annotations that count on a method, each with its rule, and [onMethod] is its names.
 * [onParameter] holds the ones that count on a parameter, which `@Observes` and `@ObservesAsync` are
 * the only ones to use. An annotation in one set does not count in the other place:
 * `@ModelAttribute` on a parameter marks nothing.
 *
 * Left out on purpose: Spring's `@HttpExchange` family, which marks client interfaces; servlet
 * annotations, which sit on classes; `@Reflective`, which `@Async` also carries; and any rule that
 * takes every out-of-scope runtime annotation.
 */
internal object CallbackAnnotations {
    private val rules = LinkedHashMap<String, Inheritance>()
    private val jaxRsNames = HashSet<String>()

    /** Each annotation that counts when it sits on the method, with the rule its framework follows. */
    val inheritance: Map<String, Inheritance>

    /** The annotations that count when they sit on the method. */
    val onMethod: Set<String>
        get() = inheritance.keys

    /**
     * The JAX-RS annotations among [onMethod], in both namespaces. A method that carries any
     * annotation from a `ws.rs` package inherits none of these (REST 4.0 section 3.6).
     */
    val jaxRs: Set<String>

    /** Annotations that count when they sit on a parameter of the method. */
    val onParameter: Set<String> =
        // CDI observers, in both namespaces.
        listOf("jakarta", "javax").flatMapTo(HashSet()) { root ->
            listOf("Observes", "ObservesAsync").map { "$root/enterprise/event/$it" }
        }

    init {
        val web = Inheritance.FROM_INTERFACES_AND_SUPERCLASSES
        val never = Inheritance.NEVER
        // Spring Framework: events, scheduling, beans, web and messaging.
        add(
            web,
            "org.springframework.context.event.EventListener",
            "org.springframework.transaction.event.TransactionalEventListener",
            "org.springframework.messaging.handler.annotation.MessageMapping",
            "org.springframework.messaging.handler.annotation.MessageExceptionHandler",
            "org.springframework.messaging.simp.annotation.SubscribeMapping",
        )
        add(
            never,
            "org.springframework.scheduling.annotation.Scheduled",
            "org.springframework.scheduling.annotation.Schedules",
            "org.springframework.jms.annotation.JmsListener",
            "org.springframework.jms.annotation.JmsListeners",
        )
        add(Inheritance.FROM_SUPERCLASSES_AND_DEFAULT_METHODS, "org.springframework.context.annotation.Bean")
        inPackage(
            web,
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
        inPackage(web, "org.springframework.kafka.annotation", "KafkaListener", "KafkaListeners", "KafkaHandler")
        inPackage(web, "org.springframework.amqp.rabbit.annotation", "RabbitListener", "RabbitListeners", "RabbitHandler")
        inPackage(
            web,
            "org.springframework.graphql.data.method.annotation",
            "SchemaMapping",
            "QueryMapping",
            "MutationMapping",
            "SubscriptionMapping",
            "BatchMapping",
            "GraphQlExceptionHandler",
        )
        inPackage(
            never,
            "org.springframework.boot.actuate.endpoint.annotation",
            "ReadOperation",
            "WriteOperation",
            "DeleteOperation",
        )
        // Lifecycle callbacks, in both namespaces.
        inBothNamespaces(never, "annotation", "PostConstruct", "PreDestroy")
        // JAX-RS. HttpMethod is the meta-annotation on every verb, so a custom verb is found through it.
        for (root in listOf("jakarta", "javax")) {
            val names = listOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "Path", "HttpMethod")
            inPackage(web, "$root.ws.rs", *names.toTypedArray())
            names.mapTo(jaxRsNames) { "$root/ws/rs/$it" }
        }
        inBothNamespaces(never, "ejb", "Schedule", "Schedules", "Timeout")
        inBothNamespaces(never, "interceptor", "AroundInvoke", "AroundTimeout")
        inBothNamespaces(never, "websocket", "OnMessage", "OnOpen", "OnClose", "OnError")
        inBothNamespaces(
            never,
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
        inBothNamespaces(never, "inject", "Inject")
        // Micronaut. HttpMethodMapping is the meta-annotation on @Get, @Post, @Error and the rest.
        add(
            web,
            "io.micronaut.runtime.event.annotation.EventListener",
            "io.micronaut.http.annotation.HttpMethodMapping",
        )
        add(
            never,
            "io.micronaut.scheduling.annotation.Scheduled",
            "io.micronaut.scheduling.annotation.Schedules",
            "io.quarkus.scheduler.Scheduled",
            "io.quarkus.scheduler.Scheduled\$Schedules",
            "io.quarkus.runtime.Startup",
        )
        add(web, "com.google.common.eventbus.Subscribe")
        inheritance = rules
        jaxRs = jaxRsNames
    }

    /** Adds full dotted names under [rule]. */
    private fun add(
        rule: Inheritance,
        vararg fullNames: String,
    ) {
        fullNames.forEach { rules[it.replace('.', '/')] = rule }
    }

    /** Adds each of [simpleNames] under the package [prefix], with [rule]. */
    private fun inPackage(
        rule: Inheritance,
        prefix: String,
        vararg simpleNames: String,
    ) {
        simpleNames.forEach { rules["${prefix.replace('.', '/')}/$it"] = rule }
    }

    /** Adds each of [simpleNames] under both `jakarta.<packageName>` and `javax.<packageName>`, with [rule]. */
    private fun inBothNamespaces(
        rule: Inheritance,
        packageName: String,
        vararg simpleNames: String,
    ) {
        for (root in listOf("jakarta", "javax")) inPackage(rule, "$root.$packageName", *simpleNames)
    }
}
