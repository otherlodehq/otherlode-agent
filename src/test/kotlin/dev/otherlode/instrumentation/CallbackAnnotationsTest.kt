package dev.otherlode.instrumentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pins the rule every built-in callback annotation follows (ADR 0069). */
class CallbackAnnotationsTest {
    private fun names(vararg dotted: String): Set<String> = dotted.mapTo(HashSet()) { it.replace('.', '/') }

    private fun bothNamespaces(
        packageName: String,
        vararg simpleNames: String,
    ): Set<String> =
        listOf("jakarta", "javax").flatMapTo(HashSet()) { root ->
            simpleNames.map {
                "$root/${packageName.replace('.', '/')}/$it"
            }
        }

    private val fromInterfacesAndSuperclasses: Set<String> =
        names(
            "org.springframework.context.event.EventListener",
            "org.springframework.transaction.event.TransactionalEventListener",
            "org.springframework.web.bind.annotation.RequestMapping",
            "org.springframework.web.bind.annotation.GetMapping",
            "org.springframework.web.bind.annotation.PostMapping",
            "org.springframework.web.bind.annotation.PutMapping",
            "org.springframework.web.bind.annotation.DeleteMapping",
            "org.springframework.web.bind.annotation.PatchMapping",
            "org.springframework.web.bind.annotation.ExceptionHandler",
            "org.springframework.web.bind.annotation.ModelAttribute",
            "org.springframework.web.bind.annotation.InitBinder",
            "org.springframework.messaging.handler.annotation.MessageMapping",
            "org.springframework.messaging.handler.annotation.MessageExceptionHandler",
            "org.springframework.messaging.simp.annotation.SubscribeMapping",
            "org.springframework.kafka.annotation.KafkaListener",
            "org.springframework.kafka.annotation.KafkaListeners",
            "org.springframework.kafka.annotation.KafkaHandler",
            "org.springframework.amqp.rabbit.annotation.RabbitListener",
            "org.springframework.amqp.rabbit.annotation.RabbitListeners",
            "org.springframework.amqp.rabbit.annotation.RabbitHandler",
            "org.springframework.graphql.data.method.annotation.SchemaMapping",
            "org.springframework.graphql.data.method.annotation.QueryMapping",
            "org.springframework.graphql.data.method.annotation.MutationMapping",
            "org.springframework.graphql.data.method.annotation.SubscriptionMapping",
            "org.springframework.graphql.data.method.annotation.BatchMapping",
            "org.springframework.graphql.data.method.annotation.GraphQlExceptionHandler",
            "io.micronaut.http.annotation.HttpMethodMapping",
            "io.micronaut.runtime.event.annotation.EventListener",
            "com.google.common.eventbus.Subscribe",
        ) + bothNamespaces("ws.rs", "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "Path", "HttpMethod")

    private val fromSuperclassesAndDefaultMethods: Set<String> = names("org.springframework.context.annotation.Bean")

    private val never: Set<String> =
        names(
            "org.springframework.scheduling.annotation.Scheduled",
            "org.springframework.scheduling.annotation.Schedules",
            "org.springframework.jms.annotation.JmsListener",
            "org.springframework.jms.annotation.JmsListeners",
            "org.springframework.boot.actuate.endpoint.annotation.ReadOperation",
            "org.springframework.boot.actuate.endpoint.annotation.WriteOperation",
            "org.springframework.boot.actuate.endpoint.annotation.DeleteOperation",
            "io.micronaut.scheduling.annotation.Scheduled",
            "io.micronaut.scheduling.annotation.Schedules",
            "io.quarkus.scheduler.Scheduled",
            "io.quarkus.scheduler.Scheduled\$Schedules",
            "io.quarkus.runtime.Startup",
        ) +
            bothNamespaces("annotation", "PostConstruct", "PreDestroy") +
            bothNamespaces("ejb", "Schedule", "Schedules", "Timeout") +
            bothNamespaces("interceptor", "AroundInvoke", "AroundTimeout") +
            bothNamespaces("websocket", "OnMessage", "OnOpen", "OnClose", "OnError") +
            bothNamespaces(
                "persistence",
                "PrePersist",
                "PostPersist",
                "PreUpdate",
                "PostUpdate",
                "PreRemove",
                "PostRemove",
                "PostLoad",
            ) +
            bothNamespaces("inject", "Inject")

    private fun namesWith(rule: Inheritance): Set<String> = CallbackAnnotations.inheritance.filterValues { it == rule }.keys

    @Test
    fun `every built-in name has exactly the rule its framework follows`() {
        assertEquals(fromInterfacesAndSuperclasses, namesWith(Inheritance.FROM_INTERFACES_AND_SUPERCLASSES))
        assertEquals(fromSuperclassesAndDefaultMethods, namesWith(Inheritance.FROM_SUPERCLASSES_AND_DEFAULT_METHODS))
        assertEquals(never, namesWith(Inheritance.NEVER))
    }

    @Test
    fun `the three groups together are every name that counts on a method, and none is in two`() {
        val all = fromInterfacesAndSuperclasses + fromSuperclassesAndDefaultMethods + never

        assertEquals(CallbackAnnotations.onMethod, all)
        assertEquals(all.size, fromInterfacesAndSuperclasses.size + fromSuperclassesAndDefaultMethods.size + never.size)
    }

    @Test
    fun `the JAX-RS names are the ws rs ones in both namespaces, all inheriting from interfaces and superclasses`() {
        assertEquals(
            bothNamespaces("ws.rs", "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "Path", "HttpMethod"),
            CallbackAnnotations.jaxRs,
        )
        assertTrue(CallbackAnnotations.jaxRs.all { CallbackAnnotations.inheritance[it] == Inheritance.FROM_INTERFACES_AND_SUPERCLASSES })
    }

    @Test
    fun `a rule allows the relations its framework honours`() {
        assertEquals(
            setOf(Relation.SUPERCLASS, Relation.INTERFACE_DEFAULT, Relation.INTERFACE_ABSTRACT),
            Inheritance.FROM_INTERFACES_AND_SUPERCLASSES.relations,
        )
        assertEquals(setOf(Relation.SUPERCLASS, Relation.INTERFACE_DEFAULT), Inheritance.FROM_SUPERCLASSES_AND_DEFAULT_METHODS.relations)
        assertEquals(emptySet(), Inheritance.NEVER.relations)
    }
}
