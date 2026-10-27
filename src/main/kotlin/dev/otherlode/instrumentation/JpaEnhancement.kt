package dev.otherlode.instrumentation

import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.FieldVisitor
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes

/**
 * What Ebean, EclipseLink and OpenJPA added to one entity class (ADR 0079), read from the class
 * file. Each rule applies only when the class's direct interfaces include the enhancer's marker, so
 * a class no enhancer touched reads as [NONE].
 *
 * [unprobed] holds the enhancer's methods: they get no probe and no declaration, and a call to one
 * passes through. [reportedNames] maps an OpenJPA accessor the enhancer moved to `pc<name>` to the
 * adopter's `<name>`, which is the name its probes and call edges carry.
 * [isPersistenceWeaved] says the class holds EclipseLink's weaving, and [hasLineNumbers] says which
 * of its methods carry a line-number table.
 */
internal class JpaEnhancement private constructor(
    val unprobed: Set<Pair<String, String>>,
    val reportedNames: Map<Pair<String, String>, String>,
    val isPersistenceWeaved: Boolean,
    private val linedMethods: Set<Pair<String, String>>,
) {
    /** Whether [name]/[descriptor] is a method the enhancer added. */
    fun isUnprobed(
        name: String,
        descriptor: String,
    ): Boolean = (name to descriptor) in unprobed

    /** The name [name]/[descriptor] reports under: the adopter's name for a moved OpenJPA accessor, otherwise [name]. */
    fun reportedName(
        name: String,
        descriptor: String,
    ): String = reportedNames[name to descriptor] ?: name

    /**
     * [byMethod] with each moved OpenJPA accessor given its wrapper's value, when the wrapper has one.
     * The enhancer moves the adopter's runtime-visible method annotations to the wrapper, and only the
     * wrapper overrides a supertype's method.
     */
    fun <V> fromWrappers(byMethod: Map<Pair<String, String>, V>): Map<Pair<String, String>, V> {
        if (reportedNames.isEmpty()) return byMethod
        val result = byMethod.toMutableMap()
        for ((moved, name) in reportedNames) {
            val wrapperValue = byMethod[name to moved.second]
            if (wrapperValue != null) result[moved] = wrapperValue
        }
        return result
    }

    /** Whether the class file gives [name]/[descriptor] a line-number table. */
    fun hasLineNumbers(
        name: String,
        descriptor: String,
    ): Boolean = (name to descriptor) in linedMethods

    private class MethodFacts(
        val access: Int,
        var hasLineNumbers: Boolean = false,
        var instructions: Int = 0,
        var startsWithToStringBuilder: Boolean = false,
        var callsToStringBuilderOverload: Boolean = false,
        var callsEbeanIdentity: Boolean = false,
    )

    companion object {
        /** A class no enhancer touched. */
        val NONE = JpaEnhancement(emptySet(), emptyMap(), false, emptySet())

        private const val ENTITY_BEAN = "io/ebean/bean/EntityBean"
        private const val PERSISTENCE_WEAVED = "org/eclipse/persistence/internal/weaving/PersistenceWeaved"
        private const val PERSISTENCE_CAPABLE = "org/apache/openjpa/enhance/PersistenceCapable"

        private const val EBEAN_PREFIX = "_ebean_"
        private const val TO_STRING_BUILDER = "io/ebean/bean/ToStringBuilder"
        private const val TO_STRING_BUILDER_OVERLOAD = "(Lio/ebean/bean/ToStringBuilder;)V"
        private const val EBEAN_COPY_CONSTRUCTOR = "(Lio/ebean/bean/EntityBean;)V"
        private const val EBEAN_IDENTITY = "_ebean_getIdentity"
        private val EBEAN_IDENTITY_USERS = setOf("equals" to "(Ljava/lang/Object;)Z", "hashCode" to "()I")

        private const val ECLIPSELINK_PREFIX = "_persistence_"
        private const val ECLIPSELINK_COPY_CONSTRUCTOR = "(Lorg/eclipse/persistence/internal/descriptors/PersistenceObject;)V"

        private const val OPENJPA_PREFIX = "pc"
        private const val STATE_MANAGER = "Lorg/apache/openjpa/enhance/StateManager;"
        private const val PERSISTENCE_CAPABLE_DESCRIPTOR = "Lorg/apache/openjpa/enhance/PersistenceCapable;"
        private const val NEW_INSTANCE = "pcNewInstance"

        /** The names of the interfaces that mark a class an enhancer touched, in internal form. */
        private val MARKERS = setOf(ENTITY_BEAN, PERSISTENCE_WEAVED, PERSISTENCE_CAPABLE)

        /**
         * OpenJPA 4.1.1's fixed methods (`PCEnhancer`), by name and descriptor. `pcCopyField` takes
         * the entity's own type, so it is added per class in [openJpaFixed].
         */
        private val OPENJPA_FIXED: Set<Pair<String, String>> =
            setOf(
                "pcGetEnhancementContractVersion" to "()I",
                "pcClearFields" to "()V",
                NEW_INSTANCE to "(${STATE_MANAGER}Ljava/lang/Object;Z)$PERSISTENCE_CAPABLE_DESCRIPTOR",
                NEW_INSTANCE to "(${STATE_MANAGER}Z)$PERSISTENCE_CAPABLE_DESCRIPTOR",
                "pcGetManagedFieldCount" to "()I",
                "pcReplaceField" to "(I)V",
                "pcReplaceFields" to "([I)V",
                "pcProvideField" to "(I)V",
                "pcProvideFields" to "([I)V",
                "pcCopyFields" to "(Ljava/lang/Object;[I)V",
                "pcGetGenericContext" to "()Ljava/lang/Object;",
                "pcFetchObjectId" to "()Ljava/lang/Object;",
                "pcIsDeleted" to "()Z",
                "pcIsDirty" to "()Z",
                "pcIsNew" to "()Z",
                "pcIsPersistent" to "()Z",
                "pcIsTransactional" to "()Z",
                "pcIsDetached" to "()Ljava/lang/Boolean;",
                "pcSerializing" to "()Z",
                "pcDirty" to "(Ljava/lang/String;)V",
                "pcGetStateManager" to "()$STATE_MANAGER",
                "pcReplaceStateManager" to "($STATE_MANAGER)V",
                "pcGetVersion" to "()Ljava/lang/Object;",
                "pcCopyKeyFieldsToObjectId" to "(Lorg/apache/openjpa/enhance/FieldSupplier;Ljava/lang/Object;)V",
                "pcCopyKeyFieldsToObjectId" to "(Ljava/lang/Object;)V",
                "pcCopyKeyFieldsFromObjectId" to "(Lorg/apache/openjpa/enhance/FieldConsumer;Ljava/lang/Object;)V",
                "pcCopyKeyFieldsFromObjectId" to "(Ljava/lang/Object;)V",
                "pcNewObjectIdInstance" to "(Ljava/lang/Object;)Ljava/lang/Object;",
                "pcNewObjectIdInstance" to "()Ljava/lang/Object;",
                "pcisDetachedStateDefinitive" to "()Z",
                "pcGetDetachedState" to "()Ljava/lang/Object;",
                "pcSetDetachedState" to "(Ljava/lang/Object;)V",
                "pcAttributeIndexToFieldName" to "(I)Ljava/lang/String;",
                "pcGetIDOwningClass" to "()Ljava/lang/Class;",
                "pcReadUnmanaged" to "(Ljava/io/ObjectInput;)V",
                "pcWriteUnmanaged" to "(Ljava/io/ObjectOutput;)V",
                "writeObject" to "(Ljava/io/ObjectOutputStream;)V",
                "readObject" to "(Ljava/io/ObjectInputStream;)V",
                "readExternalFields" to "(Ljava/io/ObjectInput;)V",
                "writeExternalFields" to "(Ljava/io/ObjectOutput;)V",
            )

        private val OPENJPA_READ_EXTERNAL = "readExternal" to "(Ljava/io/ObjectInput;)V"
        private val OPENJPA_WRITE_EXTERNAL = "writeExternal" to "(Ljava/io/ObjectOutput;)V"

        /**
         * Whether [name], with no declaration in the owner's class file, is one the Ebean enhancer
         * could have added. Under runtime enhancement the class file predates the enhancer, so the
         * interface rule has nothing to read there and the prefix decides.
         */
        fun isEbeanName(name: String): Boolean = name.startsWith(EBEAN_PREFIX)

        /**
         * The methods of [classFile] that OpenJPA wrapped in [received] as the class loaded: each
         * `m` for which [received] declares `pc<m>` with the same descriptor and [classFile] does
         * not. Empty when [received]'s direct interfaces do not name OpenJPA's marker. No line table
         * is read, so a class compiled without line numbers is covered too.
         */
        fun loadTimeWrappers(
            classFile: ByteArray,
            received: ByteArray,
        ): Set<Pair<String, String>> {
            val receivedReader = ClassReader(received)
            if (PERSISTENCE_CAPABLE !in receivedReader.interfaces) return emptySet()
            val inReceived = declaredMethods(receivedReader)
            val inClassFile = declaredMethods(ClassReader(classFile))
            return inClassFile.filterTo(HashSet()) { (name, descriptor) ->
                val moved = (OPENJPA_PREFIX + name) to descriptor
                moved in inReceived && moved !in inClassFile
            }
        }

        private fun declaredMethods(reader: ClassReader): Set<Pair<String, String>> {
            val methods = HashSet<Pair<String, String>>()
            reader.accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitMethod(
                        access: Int,
                        name: String,
                        descriptor: String,
                        signature: String?,
                        exceptions: Array<out String>?,
                    ): MethodVisitor? {
                        methods += name to descriptor
                        return null
                    }
                },
                ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
            )
            return methods
        }

        /** What [classBytes] holds of an enhancer's work; see [of]. */
        fun of(classBytes: ByteArray): JpaEnhancement = of(ClassReader(classBytes))

        /**
         * What [reader]'s class holds of an enhancer's work, or [NONE] when its direct interfaces
         * name no enhancer's marker. That check reads only the class header.
         */
        fun of(reader: ClassReader): JpaEnhancement {
            val interfaces = reader.interfaces
            if (interfaces.none { it in MARKERS }) return NONE
            val ebean = ENTITY_BEAN in interfaces
            val weaved = PERSISTENCE_WEAVED in interfaces
            val openJpa = PERSISTENCE_CAPABLE in interfaces
            val methods = LinkedHashMap<Pair<String, String>, MethodFacts>()
            val fields = HashMap<String, String>()
            reader.accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitField(
                        access: Int,
                        name: String,
                        descriptor: String,
                        signature: String?,
                        value: Any?,
                    ): FieldVisitor? {
                        fields[name] = descriptor
                        return null
                    }

                    override fun visitMethod(
                        access: Int,
                        name: String,
                        descriptor: String,
                        signature: String?,
                        exceptions: Array<out String>?,
                    ): MethodVisitor {
                        val facts = MethodFacts(access)
                        methods[name to descriptor] = facts
                        return FactsVisitor(
                            facts,
                            ebean && name == "toString" && descriptor == "()Ljava/lang/String;",
                            ebean && (name to descriptor) in EBEAN_IDENTITY_USERS,
                            reader.className,
                        )
                    }
                },
                ClassReader.SKIP_FRAMES,
            )
            val unprobed = HashSet<Pair<String, String>>()
            val reported = HashMap<Pair<String, String>, String>()
            if (ebean) unprobed += ebeanMethods(methods)
            if (weaved) unprobed += eclipseLinkMethods(methods)
            if (openJpa) openJpaMethods(reader.className, methods, fields, unprobed, reported)
            val lined = methods.filterValues { it.hasLineNumbers }.keys
            return JpaEnhancement(unprobed, reported, weaved, lined)
        }

        private fun ebeanMethods(methods: Map<Pair<String, String>, MethodFacts>): Set<Pair<String, String>> =
            methods
                .filter { (key, facts) ->
                    val (name, descriptor) = key
                    name.startsWith(EBEAN_PREFIX) ||
                        (name == "<init>" && descriptor == EBEAN_COPY_CONSTRUCTOR) ||
                        (name == "toString" && descriptor == TO_STRING_BUILDER_OVERLOAD) ||
                        ((name to descriptor) in EBEAN_IDENTITY_USERS && facts.callsEbeanIdentity) ||
                        (
                            name == "toString" && descriptor == "()Ljava/lang/String;" &&
                                facts.startsWithToStringBuilder && facts.callsToStringBuilderOverload
                        )
                }.keys

        private fun eclipseLinkMethods(methods: Map<Pair<String, String>, MethodFacts>): Set<Pair<String, String>> =
            methods
                .filter { (key, facts) ->
                    val (name, descriptor) = key
                    (name.startsWith(ECLIPSELINK_PREFIX) && !facts.hasLineNumbers) ||
                        (name == "<init>" && descriptor == ECLIPSELINK_COPY_CONSTRUCTOR)
                }.keys

        private fun openJpaMethods(
            owner: String,
            methods: Map<Pair<String, String>, MethodFacts>,
            fields: Map<String, String>,
            unprobed: MutableSet<Pair<String, String>>,
            reported: MutableMap<Pair<String, String>, String>,
        ) {
            val fixed = openJpaFixed(owner, methods)
            val enhancer = HashSet<Pair<String, String>>()
            for ((key, facts) in methods) {
                if (facts.hasLineNumbers) continue
                val (name, descriptor) = key
                if (key in fixed || isOpenJpaFieldAccessor(owner, name, descriptor, facts.access, fields)) enhancer += key
            }
            unprobed += enhancer
            for ((key, facts) in methods) {
                val (name, descriptor) = key
                if (facts.hasLineNumbers || key in enhancer || name == "<init>" || name == "<clinit>") continue
                if (facts.access and (Opcodes.ACC_ABSTRACT or Opcodes.ACC_NATIVE) != 0) continue
                val moved = (OPENJPA_PREFIX + name) to descriptor
                val movedFacts = methods[moved] ?: continue
                if (!movedFacts.hasLineNumbers || moved in fixed) continue
                unprobed += key
                reported[moved] = name
            }
        }

        private fun openJpaFixed(
            owner: String,
            methods: Map<Pair<String, String>, MethodFacts>,
        ): Set<Pair<String, String>> {
            val fixed = HashSet(OPENJPA_FIXED)
            fixed += "pcCopyField" to "(L$owner;I)V"
            if (("pcReadUnmanaged" to "(Ljava/io/ObjectInput;)V") in methods) fixed += OPENJPA_READ_EXTERNAL
            if (("pcWriteUnmanaged" to "(Ljava/io/ObjectOutput;)V") in methods) fixed += OPENJPA_WRITE_EXTERNAL
            return fixed
        }

        private fun isOpenJpaFieldAccessor(
            owner: String,
            name: String,
            descriptor: String,
            access: Int,
            fields: Map<String, String>,
        ): Boolean {
            if (access and Opcodes.ACC_STATIC == 0) return false
            if (name.startsWith("pcGet")) {
                val field = fields[name.removePrefix("pcGet")] ?: return false
                return descriptor == "(L$owner;)$field"
            }
            if (name.startsWith("pcSet")) {
                val field = fields[name.removePrefix("pcSet")] ?: return false
                return descriptor == "(L$owner;$field)V"
            }
            return false
        }

        /**
         * Whether a branch about to be visited, given the instructions before it as
         * [WovenOperand]s, most recent first, reads state EclipseLink's weaving added.
         */
        fun readsWeavedState(
            mostRecent: WovenOperand,
            secondMostRecent: WovenOperand,
        ): Boolean =
            when (mostRecent) {
                is WovenOperand.FieldRead -> {
                    mostRecent.name.startsWith(ECLIPSELINK_PREFIX)
                }

                is WovenOperand.Call -> {
                    mostRecent.name.startsWith(ECLIPSELINK_PREFIX) ||
                        (
                            mostRecent.name == "isInstantiated" && mostRecent.descriptor == "()Z" &&
                                secondMostRecent is WovenOperand.FieldRead &&
                                secondMostRecent.name.startsWith(ECLIPSELINK_PREFIX) && secondMostRecent.name.endsWith("_vh")
                        )
                }

                WovenOperand.Other -> {
                    false
                }
            }
    }

    /** One of the instructions before a branch, as far as [readsWeavedState] reads it. */
    sealed interface WovenOperand {
        /** A `getfield` of [name]. */
        data class FieldRead(
            val name: String,
        ) : WovenOperand

        /** A call of [name] with [descriptor]. */
        data class Call(
            val name: String,
            val descriptor: String,
        ) : WovenOperand

        /** Any other instruction. */
        data object Other : WovenOperand
    }

    private class FactsVisitor(
        private val facts: MethodFacts,
        private val readsToString: Boolean,
        private val readsIdentity: Boolean,
        private val ownerInternalName: String,
    ) : MethodVisitor(Opcodes.ASM9) {
        override fun visitLineNumber(
            line: Int,
            start: Label,
        ) {
            facts.hasLineNumbers = true
        }

        private fun instruction() {
            facts.instructions++
        }

        override fun visitInsn(opcode: Int) = instruction()

        override fun visitIntInsn(
            opcode: Int,
            operand: Int,
        ) = instruction()

        override fun visitVarInsn(
            opcode: Int,
            varIndex: Int,
        ) = instruction()

        override fun visitTypeInsn(
            opcode: Int,
            type: String,
        ) {
            if (readsToString && facts.instructions == 0 && opcode == Opcodes.NEW && type == TO_STRING_BUILDER) {
                facts.startsWithToStringBuilder = true
            }
            instruction()
        }

        override fun visitFieldInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
        ) = instruction()

        override fun visitMethodInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
            isInterface: Boolean,
        ) {
            if (readsToString && opcode == Opcodes.INVOKEVIRTUAL && owner == ownerInternalName &&
                name == "toString" && descriptor == TO_STRING_BUILDER_OVERLOAD
            ) {
                facts.callsToStringBuilderOverload = true
            }
            if (readsIdentity && owner == ownerInternalName && name == EBEAN_IDENTITY && descriptor == "()Ljava/lang/Object;") {
                facts.callsEbeanIdentity = true
            }
            instruction()
        }

        override fun visitJumpInsn(
            opcode: Int,
            label: Label,
        ) = instruction()

        override fun visitLdcInsn(value: Any?) = instruction()
    }
}
