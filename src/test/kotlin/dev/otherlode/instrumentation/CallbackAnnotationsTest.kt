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
            "org.axonframework.messaging.annotation.MessageHandler",
            "org.axonframework.messaging.core.annotation.MessageHandler",
        ) + bothNamespaces("ws.rs", "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "Path", "HttpMethod")

    private val fromSuperclassesAndDefaultMethods: Set<String> = names("org.springframework.context.annotation.Bean")

    private val fromInterfacesOnly: Set<String> =
        names(
            "io.temporal.workflow.WorkflowMethod",
            "io.temporal.workflow.SignalMethod",
            "io.temporal.workflow.QueryMethod",
            "io.temporal.workflow.UpdateMethod",
            "io.temporal.workflow.UpdateValidatorMethod",
        )

    private val directOnly: Set<String> = names("io.temporal.nexus.TemporalOperation")

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
            "org.axonframework.lifecycle.StartHandler",
            "org.axonframework.lifecycle.ShutdownHandler",
            "org.axonframework.eventsourcing.annotation.reflection.EntityCreator",
            "org.axonframework.eventsourcing.annotation.EventCriteriaBuilder",
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
        assertEquals(fromInterfacesOnly, namesWith(Inheritance.FROM_INTERFACES_ONLY))
        assertEquals(directOnly, namesWith(Inheritance.DIRECT_ONLY))
    }

    @Test
    fun `the groups together are every name that has a rule, and none is in two`() {
        val all = fromInterfacesAndSuperclasses + fromSuperclassesAndDefaultMethods + fromInterfacesOnly + directOnly + never

        assertEquals(CallbackAnnotations.inheritance.keys, all)
        assertEquals(
            all.size,
            fromInterfacesAndSuperclasses.size + fromSuperclassesAndDefaultMethods.size + fromInterfacesOnly.size + directOnly.size +
                never.size,
        )
    }

    @Test
    fun `a name counts on its own method unless its rule says it does not`() {
        assertEquals(
            CallbackAnnotations.inheritance.keys - fromInterfacesOnly,
            CallbackAnnotations.inheritance.filterValues { it.countsOnMethod }.keys,
        )
    }

    @Test
    fun `the Temporal names are read directly, and no other name is`() {
        val direct = CallbackAnnotations.inheritance.filterValues { it.direct }.keys

        assertEquals(fromInterfacesOnly + directOnly, direct)
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
        assertEquals(setOf(Relation.INTERFACE_DEFAULT, Relation.INTERFACE_ABSTRACT), Inheritance.FROM_INTERFACES_ONLY.relations)
        assertEquals(emptySet(), Inheritance.DIRECT_ONLY.relations)
    }
}
