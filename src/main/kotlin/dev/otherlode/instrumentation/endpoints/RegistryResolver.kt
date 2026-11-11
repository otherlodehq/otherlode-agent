package dev.otherlode.instrumentation.endpoints

import dev.otherlode.agentLogger
import dev.otherlode.bootstrap.OtherlodeEndpoints
import dev.otherlode.export.DisabledEndpointModuleKind
import dev.otherlode.export.EndpointTemplateKind
import dev.otherlode.instrumentation.endpoints.api.EndpointModule
import dev.otherlode.registry.DeclaringClass
import dev.otherlode.registry.EndpointRegistry
import dev.otherlode.registry.HandlerRef
import java.lang.System.Logger.Level
import java.util.concurrent.ConcurrentHashMap

/**
 * Adapts [EndpointRegistry] to the [OtherlodeEndpoints.Resolver] the endpoint seam calls through.
 * [modules], indexed by [EndpointModule.name], is who [declare] routes a framework object to.
 *
 * A handler join that names a method passes through [handlerForwarders] in [register] and
 * [attachHandler], so a pass-through becomes the method it forwards to. [recordDispatch] names only
 * a class and is left as reported.
 */
class RegistryResolver(
    private val registry: EndpointRegistry,
    modules: List<EndpointModule> = emptyList(),
    private val pendingDeclarations: PendingDeclarations,
    private val handlerForwarders: HandlerForwarders = HandlerForwarders(),
) : OtherlodeEndpoints.Resolver {
    private val log by agentLogger(RegistryResolver::class.java.name)
    private val modulesByName = modules.associateBy { it.name }
    private val declareFailureLogged = ConcurrentHashMap.newKeySet<String>()
    private val unknownDeclareModuleLogged = ConcurrentHashMap.newKeySet<String>()

    override fun lookup(key: Any): Any? = registry.lookup(key)

    /**
     * Declares an endpoint, or holds it until the transform that declared it has produced bytes (see
     * [PendingDeclarations]). A held declaration returns null.
     */
    override fun register(
        key: Any,
        framework: String,
        verb: String?,
        verbatimTemplate: String,
        contextPath: String?,
        handlerClass: String?,
        handlerMethod: String?,
        handlerDescriptor: String?,
        templateKind: Int,
    ): Any? {
        fun declare(declaring: DeclaringClass?) =
            registry.register(
                key = key,
                framework = framework,
                verb = verb,
                verbatimTemplate = verbatimTemplate,
                contextPath = contextPath,
                handler = handlerRef(handlerClass, handlerMethod, handlerDescriptor),
                templateKind = kindOf(templateKind),
                declaringClass = declaring,
            )
        if (pendingDeclarations.stage { declare(it) }) return null
        return declare(null)
    }

    private fun kindOf(templateKind: Int): EndpointTemplateKind =
        when (templateKind) {
            OtherlodeEndpoints.TEMPLATE_PATH -> EndpointTemplateKind.PATH
            OtherlodeEndpoints.TEMPLATE_REGEX -> EndpointTemplateKind.REGEX
            OtherlodeEndpoints.TEMPLATE_SERVLET_PATTERN -> EndpointTemplateKind.SERVLET_PATTERN
            else -> throw IllegalArgumentException("unknown endpoint template kind $templateKind")
        }

    private fun handlerRef(
        handlerClass: String?,
        handlerMethod: String?,
        handlerDescriptor: String?,
    ): HandlerRef? = handlerClass?.let { handlerForwarders.collapse(HandlerRef(it, handlerMethod, handlerDescriptor)) }

    override fun recordDispatch(
        key: Any,
        framework: String,
        verb: String?,
        verbatimTemplate: String,
        contextPath: String?,
        handlerClass: String?,
        templateKind: Int,
    ): Any? = registry.recordDispatch(key, framework, verb, verbatimTemplate, contextPath, handlerClass, kindOf(templateKind))

    override fun recordDispatchIfUnowned(
        key: Any,
        framework: String,
        verb: String?,
        verbatimTemplate: String,
        contextPath: String?,
        handlerClass: String?,
        templateKind: Int,
    ): Any? = registry.recordDispatchIfUnowned(key, framework, verb, verbatimTemplate, contextPath, handlerClass, kindOf(templateKind))

    /**
     * Routes [frameworkObject] to the [EndpointModule] named [module], which registers what it finds.
     * An unknown module name is logged once and ignored. A module whose walk throws goes through
     * [OtherlodeEndpoints.moduleFailed], like an advice failure.
     */
    override fun declare(
        module: String,
        frameworkObject: Any,
    ) {
        val target = modulesByName[module]
        if (target == null) {
            if (unknownDeclareModuleLogged.add(module)) {
                log.log(Level.WARNING, "otherlode: declare called for unknown endpoint module '$module', ignoring")
            }
            return
        }
        try {
            target.declare(frameworkObject)
        } catch (t: Throwable) {
            if (declareFailureLogged.add(module)) {
                log.log(Level.WARNING, "otherlode: endpoint module $module failed while declaring its routes", t)
            }
            OtherlodeEndpoints.moduleFailed(module, OtherlodeEndpoints.KIND_ROUTE_WALK_FAILED, t)
        }
    }

    override fun hit(entry: Any) {
        (entry as EndpointRegistry.EndpointEntry).hit()
    }

    override fun attachHandler(
        entry: Any,
        handlerClass: String?,
        handlerMethod: String?,
        handlerDescriptor: String?,
    ) {
        val handler = handlerRef(handlerClass, handlerMethod, handlerDescriptor) ?: return
        registry.attachHandler(entry as EndpointRegistry.EndpointEntry, handler)
    }

    override fun disableModule(
        module: String,
        kind: Int,
        reason: String,
    ) {
        registry.recordDisabledModule(module, reason, disabledKindOf(kind))
    }
}

internal fun disabledKindOf(kind: Int): DisabledEndpointModuleKind =
    when (kind) {
        OtherlodeEndpoints.KIND_LINKAGE_ERROR -> DisabledEndpointModuleKind.LINKAGE_ERROR
        OtherlodeEndpoints.KIND_ADVICE_FAILED -> DisabledEndpointModuleKind.ADVICE_FAILED
        OtherlodeEndpoints.KIND_TRANSFORM_FAILED -> DisabledEndpointModuleKind.TRANSFORM_FAILED
        OtherlodeEndpoints.KIND_ROUTE_WALK_FAILED -> DisabledEndpointModuleKind.ROUTE_WALK_FAILED
        OtherlodeEndpoints.KIND_HOOK_UNMATCHED -> DisabledEndpointModuleKind.HOOK_UNMATCHED
        OtherlodeEndpoints.KIND_ROUTES_NOT_COUNTED -> DisabledEndpointModuleKind.ROUTES_NOT_COUNTED
        else -> DisabledEndpointModuleKind.UNSPECIFIED
    }
