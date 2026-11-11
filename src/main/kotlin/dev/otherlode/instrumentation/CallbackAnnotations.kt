package dev.otherlode.instrumentation

internal enum class Relation {
    SUPERCLASS,

    INTERFACE_DEFAULT,

    INTERFACE_ABSTRACT,
}

internal enum class Inheritance(
    val relations: Set<Relation>,
    val countsOnMethod: Boolean = true,
    val direct: Boolean = false,
) {
    FROM_INTERFACES_AND_SUPERCLASSES(Relation.entries.toSet()),

    FROM_SUPERCLASSES_AND_DEFAULT_METHODS(setOf(Relation.SUPERCLASS, Relation.INTERFACE_DEFAULT)),

    NEVER(emptySet()),

    FROM_INTERFACES_ONLY(setOf(Relation.INTERFACE_DEFAULT, Relation.INTERFACE_ABSTRACT), countsOnMethod = false, direct = true),

    DIRECT_ONLY(emptySet(), direct = true),
}

internal object CallbackAnnotations {
    private val rules = LinkedHashMap<String, Inheritance>()
    private val jaxRsNames = HashSet<String>()

    val inheritance: Map<String, Inheritance>

    val jaxRs: Set<String>

    const val ACTIVITY_INTERFACE = "io/temporal/activity/ActivityInterface"

    val onParameter: Set<String> =
        listOf("jakarta", "javax").flatMapTo(HashSet()) { root ->
            listOf("Observes", "ObservesAsync").map { "$root/enterprise/event/$it" }
        }

    init {
        val web = Inheritance.FROM_INTERFACES_AND_SUPERCLASSES
        val never = Inheritance.NEVER
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
        inBothNamespaces(never, "annotation", "PostConstruct", "PreDestroy")
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
        inBothNamespaces(never, "inject", "Inject")
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
        inPackage(
            Inheritance.FROM_INTERFACES_ONLY,
            "io.temporal.workflow",
            "WorkflowMethod",
            "SignalMethod",
            "QueryMethod",
            "UpdateMethod",
            "UpdateValidatorMethod",
        )
        add(Inheritance.DIRECT_ONLY, "io.temporal.nexus.TemporalOperation")
        add(
            web,
            "org.axonframework.messaging.annotation.MessageHandler",
            "org.axonframework.messaging.core.annotation.MessageHandler",
        )
        add(
            never,
            "org.axonframework.lifecycle.StartHandler",
            "org.axonframework.lifecycle.ShutdownHandler",
            "org.axonframework.eventsourcing.annotation.reflection.EntityCreator",
            "org.axonframework.eventsourcing.annotation.EventCriteriaBuilder",
        )
        inheritance = rules
        jaxRs = jaxRsNames
    }

    private fun add(
        rule: Inheritance,
        vararg fullNames: String,
    ) {
        fullNames.forEach { rules[it.replace('.', '/')] = rule }
    }

    private fun inPackage(
        rule: Inheritance,
        prefix: String,
        vararg simpleNames: String,
    ) {
        simpleNames.forEach { rules["${prefix.replace('.', '/')}/$it"] = rule }
    }

    private fun inBothNamespaces(
        rule: Inheritance,
        packageName: String,
        vararg simpleNames: String,
    ) {
        for (root in listOf("jakarta", "javax")) inPackage(rule, "$root.$packageName", *simpleNames)
    }
}
