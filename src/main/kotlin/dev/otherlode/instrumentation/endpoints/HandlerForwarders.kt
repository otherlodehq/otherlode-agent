package dev.otherlode.instrumentation.endpoints

import dev.otherlode.instrumentation.branch.HandlerForwarder
import dev.otherlode.registry.HandlerRef
import java.util.concurrent.ConcurrentHashMap

/**
 * Maps a pass-through that a framework can report as a handler to the one probed method it forwards
 * to. The method tier's analysis writes entries, only for the functional interfaces in
 * [handlerInterfaces]. [RegistryResolver] reads them.
 *
 * Two writes for one pass-through that name different targets leave no entry for it, and a miss
 * keeps the name the framework reported.
 */
class HandlerForwarders(
    /** Functional interfaces a framework takes a handler as, by `Class.getName()`. Empty turns the table off. */
    val handlerInterfaces: Set<String> = emptySet(),
) {
    private data class Key(
        val className: String,
        val methodName: String,
        val descriptor: String,
    )

    private val targets = ConcurrentHashMap<Key, HandlerRef>()

    /** Records [forwarder]. A write that names a different target than an earlier one leaves no entry. */
    fun record(forwarder: HandlerForwarder) {
        val target = HandlerRef(forwarder.targetClassName, forwarder.targetMethodName, forwarder.targetDescriptor)
        targets.merge(Key(forwarder.className, forwarder.methodName, forwarder.descriptor), target) { old, new ->
            if (old == new) old else CONFLICT
        }
    }

    /**
     * The method [handler] forwards to, when the table has an entry for it. Any other handler comes
     * back unchanged, including one reported without a method or a descriptor.
     */
    fun collapse(handler: HandlerRef): HandlerRef {
        val methodName = handler.methodName ?: return handler
        val descriptor = handler.descriptor ?: return handler
        val target = targets[Key(handler.className, methodName, descriptor)] ?: return handler
        return if (target === CONFLICT) handler else target
    }

    private companion object {
        val CONFLICT = HandlerRef("", null, null)
    }
}
