package dev.otherlode.instrumentation.branch

import dev.otherlode.export.GeneratedBy
import dev.otherlode.export.UnreadShape
import net.bytebuddy.jar.asm.AnnotationVisitor
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.FieldVisitor
import net.bytebuddy.jar.asm.Handle
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.jar.asm.signature.SignatureReader
import net.bytebuddy.jar.asm.signature.SignatureVisitor

/**
 * The kotlinx.serialization compiler plugin's output, marked from bytecode shape alone (ADR 0078).
 * Four kinds of class are read:
 *
 * - A `$serializer` class that [serializerClass] accepts. The K2 frontend marks it synthetic, so
 *   only the K1 frontend, which Kotlin 1.9 and every release under `-language-version 1.9` use,
 *   leaves it to probe. Its `serialize`, `deserialize`, `childSerializers`, `getDescriptor`,
 *   `typeParametersSerializers`, constructors and `getTypeSerialN` are [GeneratedBy.KOTLINX_SERIALIZATION].
 * - The companion, or the object, of a serializable class whose `serializer()` takes one
 *   `KSerializer` per type parameter, as [companionSerializers] reads it. The plugin refuses to
 *   compile a `serializer()` of that descriptor the adopter wrote there, so its body need not be
 *   read. `generatedSerializer()` beside `@KeepGeneratedSerializer` is read by its body.
 * - A class-form lambda that builds the lazy cached serializer, as [lambdaClass] reads it from the
 *   outer class's type initializer.
 * - A `@Serializer(forClass = ...)` object, whose `serialize`, `deserialize` and `getDescriptor`
 *   are [UnreadShape.KOTLINX_SERIALIZER_OBJECT]: the plugin fills in the ones the adopter did not
 *   write, and nothing in the bytecode tells which.
 *
 * A class is serializable when it carries `@Serializable`, or an annotation whose own class
 * carries `@MetaSerializable`. An annotation the class file does not keep, and a class compiled
 * without the plugin, are outside what these rules can see.
 *
 * Every shape was read with `javap` from kotlinc 1.9.25, 2.1.21, 2.2.21 and 2.4.20, each with its
 * own plugin. kotlinx is the adopter's library and the agent does not relocate it. The names are
 * still built at run time: `shadowJar` rewrites any string in the agent's own classes that starts
 * with `kotlin`, which includes `kotlinx`. Names under `kotlin` itself are matched by shape for the
 * same reason, as [BranchSiteAnalyzer] matches `kotlin.Metadata`.
 */
internal object KotlinxSerializationMarks {
    /** The marks one class gets. */
    class Result(
        val generated: Map<Pair<String, String>, GeneratedBy>,
        val unread: Map<Pair<String, String>, UnreadShape>,
    ) {
        companion object {
            val EMPTY = Result(emptyMap(), emptyMap())
        }
    }

    private val ROOT = String(charArrayOf('k', 'o', 't', 'l', 'i', 'n', 'x'))
    private val SERIALIZATION = "$ROOT/serialization"
    private val K_SERIALIZER = "$SERIALIZATION/KSerializer"
    private val K_SERIALIZER_DESC = "L$K_SERIALIZER;"
    private val GENERATED_SERIALIZER = "$SERIALIZATION/internal/GeneratedSerializer"
    private val SERIALIZABLE_DESC = "L$SERIALIZATION/Serializable;"
    private val META_SERIALIZABLE_DESC = "L$SERIALIZATION/MetaSerializable;"
    private val SERIALIZER_DESC = "L$SERIALIZATION/Serializer;"
    private val KEEP_GENERATED_DESC = "L$SERIALIZATION/KeepGeneratedSerializer;"
    private val ENCODER_DESC = "L$SERIALIZATION/encoding/Encoder;"
    private val DECODER_DESC = "L$SERIALIZATION/encoding/Decoder;"
    private val SERIAL_DESCRIPTOR_DESC = "L$SERIALIZATION/descriptors/SerialDescriptor;"

    private val DEPRECATED_DESC = Regex("^L[^/;]+/Deprecated;$")
    private val DEPRECATION_LEVEL_DESC = Regex("^L[^/;]+/DeprecationLevel;$")
    private val LAMBDA_BASE = Regex("^[^/]+/jvm/internal/Lambda$")
    private val LAZY_MODE = Regex("^[^/]+/LazyThreadSafetyMode$")
    private val LAZY_KT = Regex("^[^/]+/LazyKt$")
    private val LAZY_DESC = Regex("^L[^/;]+/Lazy;$")
    private val LAZY_FACTORY_DESC = Regex("^\\(L[^/;]+/LazyThreadSafetyMode;L[^/;]+/jvm/functions/Function0;\\)L[^/;]+/Lazy;$")
    private val FUNCTION0 = Regex("^[^/]+/jvm/functions/Function0$")
    private val SERIALIZE_NAME = Regex("^serialize(?:-[A-Za-z0-9_-]{7})?$")
    private val ONE_VALUE = Regex("^\\[*(?:L[^;]+;|[ZBCSIJFD])$")
    private val MARKER_CONSTRUCTOR_DESC = Regex("^\\(L[^;]+/jvm/internal/DefaultConstructorMarker;\\)V$")
    private val DESERIALIZE_NAME = Regex("^deserialize(?:-[A-Za-z0-9_-]{7})?$")
    private val TYPE_SERIAL_NAME = Regex("^getTypeSerial\\d+$")
    private val GENERATED_SERIALIZER_NAME = Regex("^generatedSerializer(?:\\$.+)?$")

    private const val SERIALIZER_SUFFIX = "\$\$serializer"
    private const val SERIALIZER_SIMPLE_NAME = "\$serializer"
    private const val INSTANCE = "INSTANCE"
    private const val CACHED_DELEGATE = "\$cachedSerializer\$delegate"
    private const val CHILD_SERIALIZERS = "\$childSerializers"
    private const val TYPE_SERIAL_PREFIX = "typeSerial"
    private const val CLINIT = "<clinit>"

    /**
     * What the plugin made in the class [internalClassName]. [classBytes] is the class,
     * [methodAccess] its declared methods, and [lookup] reads another class by internal name. The
     * result is empty without reading anything when the class has none of the outlines the four
     * rules look for.
     */
    fun of(
        classBytes: ByteArray,
        internalClassName: String,
        superInternalName: String?,
        interfaceInternalNames: List<String>,
        methodAccess: Map<Pair<String, String>, Int>,
        lookup: (internalName: String) -> ByteArray?,
    ): Result {
        if (!mayHoldPluginOutput(internalClassName, superInternalName, interfaceInternalNames, methodAccess)) return Result.EMPTY
        val self =
            readProfile(classBytes) { GENERATED_SERIALIZER_NAME.matches(it) }
                ?: return Result.EMPTY
        val generated = mutableMapOf<Pair<String, String>, GeneratedBy>()
        val unread = mutableMapOf<Pair<String, String>, UnreadShape>()
        if (internalClassName.endsWith(SERIALIZER_SUFFIX)) serializerClass(self, lookup, generated)
        companionSerializers(self, lookup, generated)
        lambdaClass(self, superInternalName, lookup, generated)
        serializerObject(self, unread)
        return if (generated.isEmpty() && unread.isEmpty()) Result.EMPTY else Result(generated, unread)
    }

    private fun mayHoldPluginOutput(
        internalClassName: String,
        superInternalName: String?,
        interfaceInternalNames: List<String>,
        methodAccess: Map<Pair<String, String>, Int>,
    ): Boolean =
        internalClassName.endsWith(SERIALIZER_SUFFIX) ||
            K_SERIALIZER in interfaceInternalNames ||
            methodAccess.keys.any { (name, descriptor) ->
                (name == "serializer" || name.startsWith("generatedSerializer")) &&
                    descriptor.startsWith("(") &&
                    descriptor.endsWith(")$K_SERIALIZER_DESC")
            } ||
            (
                superInternalName != null &&
                    LAMBDA_BASE.matches(superInternalName) &&
                    ("invoke" to "()$K_SERIALIZER_DESC") in methodAccess
            )

    private fun serializerClass(
        self: Profile,
        lookup: (String) -> ByteArray?,
        generated: MutableMap<Pair<String, String>, GeneratedBy>,
    ) {
        val entry = self.innerClasses[self.name]
        val outerName = entry?.outerName
        if (outerName == null || entry.innerName != SERIALIZER_SIMPLE_NAME) return
        if (GENERATED_SERIALIZER !in self.interfaces || !self.deprecatedHidden) return
        val ownsInstance =
            self.fields.any { it.name == INSTANCE && it.access and Opcodes.ACC_STATIC != 0 && it.descriptor == "L${self.name};" }
        val isGeneric =
            self.fields.any {
                it.name == "${TYPE_SERIAL_PREFIX}0" && it.access and Opcodes.ACC_STATIC == 0 && it.descriptor == K_SERIALIZER_DESC
            }
        if (!ownsInstance && !isGeneric) return
        val outer = lookup.profileOf(outerName) ?: return
        if (!isSerializable(outer, lookup)) return
        for (method in self.methods) {
            if (isSerializerMember(method.name, method.descriptor, isGeneric)) {
                generated[method.name to method.descriptor] = GeneratedBy.KOTLINX_SERIALIZATION
            }
        }
    }

    private fun isSerializerMember(
        name: String,
        descriptor: String,
        isGeneric: Boolean,
    ): Boolean =
        when {
            SERIALIZE_NAME.matches(name) -> descriptor.startsWith("($ENCODER_DESC") && descriptor.endsWith(")V")
            DESERIALIZE_NAME.matches(name) -> descriptor.startsWith("($DECODER_DESC)")
            name == "childSerializers" || name == "typeParametersSerializers" -> descriptor == "()[$K_SERIALIZER_DESC"
            name == "getDescriptor" -> descriptor == "()$SERIAL_DESCRIPTOR_DESC"
            name == "<init>" -> descriptor == "()V" || (isGeneric && isOnlySerializers(descriptor))
            TYPE_SERIAL_NAME.matches(name) -> descriptor == "()$K_SERIALIZER_DESC"
            else -> false
        }

    private fun isOnlySerializers(descriptor: String): Boolean {
        if (!descriptor.startsWith("(") || !descriptor.endsWith(")V")) return false
        val parameters = descriptor.substring(1, descriptor.length - 2)
        return parameters.isNotEmpty() && parameters.length % K_SERIALIZER_DESC.length == 0 &&
            parameters == K_SERIALIZER_DESC.repeat(parameters.length / K_SERIALIZER_DESC.length)
    }

    private fun companionSerializers(
        self: Profile,
        lookup: (String) -> ByteArray?,
        generated: MutableMap<Pair<String, String>, GeneratedBy>,
    ) {
        val candidates =
            self.methods.filter {
                it.access and (Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC or Opcodes.ACC_BRIDGE) == 0 &&
                    (it.name == "serializer" || GENERATED_SERIALIZER_NAME.matches(it.name)) &&
                    it.descriptor.startsWith("(") &&
                    it.descriptor.endsWith(")$K_SERIALIZER_DESC")
            }
        if (candidates.isEmpty()) return
        val owner = serializableOwnerOf(self, lookup) ?: return
        val parameterCount = typeParameterCount(owner.signature)
        val serializer = "${owner.name}$SERIALIZER_SUFFIX"
        for (method in candidates) {
            val marked =
                if (method.name == "serializer") {
                    method.descriptor == "(${K_SERIALIZER_DESC.repeat(parameterCount)})$K_SERIALIZER_DESC"
                } else {
                    method.descriptor == "()$K_SERIALIZER_DESC" &&
                        KEEP_GENERATED_DESC in owner.annotations &&
                        method.insns?.isInstanceRead(serializer) == true
                }
            if (marked) generated[method.name to method.descriptor] = GeneratedBy.KOTLINX_SERIALIZATION
        }
    }

    /** Whether [profile] carries `@Serializable`, or an annotation whose class carries `@MetaSerializable`, read through [lookup]. */
    private fun isSerializable(
        profile: Profile,
        lookup: (String) -> ByteArray?,
    ): Boolean =
        SERIALIZABLE_DESC in profile.annotations ||
            profile.annotations.any { descriptor ->
                descriptor.startsWith("L") && descriptor.endsWith(";") &&
                    lookup.profileOf(descriptor.substring(1, descriptor.length - 1))?.annotations?.contains(META_SERIALIZABLE_DESC) == true
            }

    /**
     * The class whose `serializer()` [candidate] holds: [candidate] itself when it is a serializable
     * object, or a serializable companion; otherwise the serializable class that holds [candidate]
     * as its companion. A companion sits in a static field of its outer class under its own simple
     * name, and it has a companion's outline, as [hasOnlyCompanionConstructors] reads it. Null for
     * anything else, such as a plain class with a `serializer()` of its own, or another class,
     * interface or enum the adopter nests and holds in a companion property of the same name.
     */
    private fun serializableOwnerOf(
        candidate: Profile,
        lookup: (String) -> ByteArray?,
    ): Profile? {
        val isObject =
            candidate.fields.any {
                it.name == INSTANCE && it.access and Opcodes.ACC_STATIC != 0 && it.descriptor == "L${candidate.name};"
            }
        if (isObject) return candidate.takeIf { isSerializable(it, lookup) }
        val entry = candidate.innerClasses[candidate.name] ?: return null
        val outerName = entry.outerName ?: return null
        val companionName = entry.innerName ?: return null
        val outer = lookup.profileOf(outerName) ?: return null
        val ownsCompanion =
            hasOnlyCompanionConstructors(candidate) &&
                outer.fields.any {
                    it.access and Opcodes.ACC_STATIC != 0 && it.name == companionName && it.descriptor == "L${candidate.name};"
                } &&
                outer.innerClasses[candidate.name]?.outerName == outerName
        if (!ownsCompanion) return null
        return when {
            isSerializable(candidate, lookup) -> candidate
            isSerializable(outer, lookup) -> outer
            else -> null
        }
    }

    /**
     * Whether [profile] has the outline kotlinc gives every companion: a final class that is no
     * enum, abstract class or interface, with a private no-argument constructor and at most one
     * other, a synthetic one taking a `DefaultConstructorMarker` that is not deprecated.
     */
    private fun hasOnlyCompanionConstructors(profile: Profile): Boolean {
        val notCompanionKinds = Opcodes.ACC_ENUM or Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE
        if (profile.access and Opcodes.ACC_FINAL == 0 || profile.access and notCompanionKinds != 0) return false
        val constructors = profile.methods.filter { it.name == "<init>" }
        val noArgument = constructors.filter { it.descriptor == "()V" && it.access and Opcodes.ACC_PRIVATE != 0 }
        val marker =
            constructors.filter {
                it.access and (Opcodes.ACC_SYNTHETIC or Opcodes.ACC_DEPRECATED) == Opcodes.ACC_SYNTHETIC &&
                    MARKER_CONSTRUCTOR_DESC.matches(it.descriptor)
            }
        return noArgument.size == 1 && marker.size <= 1 && constructors.size == noArgument.size + marker.size
    }

    private fun typeParameterCount(signature: String?): Int {
        if (signature == null) return 0
        var count = 0
        try {
            SignatureReader(signature).accept(
                object : SignatureVisitor(Opcodes.ASM9) {
                    override fun visitFormalTypeParameter(name: String) {
                        count++
                    }
                },
            )
        } catch (_: RuntimeException) {
            return 0
        }
        return count
    }

    private fun lambdaClass(
        self: Profile,
        superInternalName: String?,
        lookup: (String) -> ByteArray?,
        generated: MutableMap<Pair<String, String>, GeneratedBy>,
    ) {
        if (superInternalName == null || !LAMBDA_BASE.matches(superInternalName)) return
        if (self.methods.none { it.name == "invoke" && it.descriptor == "()$K_SERIALIZER_DESC" }) return
        val enclosing = self.enclosingOwner ?: return
        val owner = lookup.profileOf(enclosing, CLINIT) ?: return
        val companions = companionsOf(owner, lookup)
        val scanned =
            if (isSerializable(owner, lookup)) {
                listOf(owner) + companions
            } else {
                // A serializable companion of a plain class has its cached serializer built in the class's type initializer.
                val serializableCompanions = companions.filter { isSerializable(it, lookup) }
                if (serializableCompanions.isEmpty()) emptyList() else listOf(owner) + serializableCompanions
            }
        if (scanned.none { buildsLazyWith(it, self.name) }) return
        for (method in self.methods) {
            if (method.name == "invoke" || method.name == "<init>" || method.name == CLINIT) {
                generated[method.name to method.descriptor] = GeneratedBy.KOTLINX_SERIALIZATION
            }
        }
    }

    private fun companionsOf(
        owner: Profile,
        lookup: (String) -> ByteArray?,
    ): List<Profile> =
        owner.fields
            .filter { it.access and Opcodes.ACC_STATIC != 0 }
            .mapNotNull { field ->
                val type = field.descriptor.removePrefix("L").removeSuffix(";")
                type.takeIf { owner.innerClasses[it]?.outerName == owner.name }?.let { lookup.profileOf(it, CLINIT) }
            }

    private fun buildsLazyWith(
        holder: Profile,
        lambda: String,
    ): Boolean {
        val insns = holder.methods.firstOrNull { it.name == CLINIT && it.descriptor == "()V" }?.insns ?: return false
        for (at in 1..insns.size - 4) {
            val load = insns[at]
            val isLambdaLoad =
                load.op == Opcodes.GETSTATIC && load.owner == lambda && load.name == INSTANCE && load.descriptor == "L$lambda;"
            if (!isLambdaLoad) continue
            val mode = insns[at - 1]
            val cast = insns[at + 1]
            val factory = insns[at + 2]
            val sink = insns[at + 3]
            val isLazy =
                mode.op == Opcodes.GETSTATIC &&
                    LAZY_MODE.matches(mode.owner) &&
                    mode.name == "PUBLICATION" &&
                    cast.op == Opcodes.CHECKCAST &&
                    FUNCTION0.matches(cast.owner) &&
                    factory.op == Opcodes.INVOKESTATIC &&
                    LAZY_KT.matches(factory.owner) &&
                    factory.name == "lazy" &&
                    LAZY_FACTORY_DESC.matches(factory.descriptor)
            if (!isLazy) continue
            val storedInDelegate =
                sink.op == Opcodes.PUTSTATIC && sink.name == CACHED_DELEGATE && LAZY_DESC.matches(sink.descriptor)
            val storedInChildSerializers =
                sink.op == Opcodes.AASTORE &&
                    insns.drop(at + 4).firstOrNull { it.op == Opcodes.PUTSTATIC }?.name == CHILD_SERIALIZERS
            if (storedInDelegate || storedInChildSerializers) return true
        }
        return false
    }

    private fun serializerObject(
        self: Profile,
        unread: MutableMap<Pair<String, String>, UnreadShape>,
    ) {
        if (K_SERIALIZER !in self.interfaces || SERIALIZER_DESC !in self.annotations) return
        for (method in self.methods) {
            val isPlain = method.access and (Opcodes.ACC_SYNTHETIC or Opcodes.ACC_BRIDGE) == 0
            val name = method.name
            val descriptor = method.descriptor
            val isInterfaceMember =
                (name == "serialize" && isEncoderAndOneValue(descriptor)) ||
                    (name == "deserialize" && descriptor.startsWith("($DECODER_DESC)")) ||
                    (name == "getDescriptor" && descriptor == "()$SERIAL_DESCRIPTOR_DESC")
            if (isPlain && isInterfaceMember) unread[name to descriptor] = UnreadShape.KOTLINX_SERIALIZER_OBJECT
        }
    }

    /** Whether [descriptor] is `(Encoder, value)V` with exactly one value of any type, as `KSerializer.serialize` is. */
    private fun isEncoderAndOneValue(descriptor: String): Boolean {
        val prefix = "($ENCODER_DESC"
        if (!descriptor.startsWith(prefix) || !descriptor.endsWith(")V")) return false
        return ONE_VALUE.matches(descriptor.substring(prefix.length, descriptor.length - 2))
    }

    private fun List<Insn>.isInstanceRead(owner: String): Boolean =
        size == 3 && this[0].isInstanceField() && this[0].owner == owner && this[1].isCheckcast(K_SERIALIZER) &&
            this[2].op == Opcodes.ARETURN

    private fun Insn.isInstanceField(): Boolean = op == Opcodes.GETSTATIC && name == INSTANCE && descriptor == "L$owner;"

    private fun Insn.isCheckcast(type: String): Boolean = op == Opcodes.CHECKCAST && owner == type

    private fun ((String) -> ByteArray?).profileOf(
        internalName: String,
        codeOf: String? = null,
    ): Profile? {
        val bytes =
            try {
                this(internalName)
            } catch (_: Exception) {
                null
            } ?: return null
        return readProfile(bytes) { it == codeOf }
    }

    private fun readProfile(
        bytes: ByteArray,
        wantsCode: (methodName: String) -> Boolean,
    ): Profile? {
        var name = ""
        var interfaces: List<String> = emptyList()
        var signature: String? = null
        var classAccess = 0
        var enclosingOwner: String? = null
        var deprecatedHidden = false
        val annotations = mutableSetOf<String>()
        val innerClasses = mutableMapOf<String, InnerEntry>()
        val fields = mutableListOf<FieldInfo>()
        val methods = mutableListOf<MethodInfo>()
        val visitor =
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(
                    version: Int,
                    access: Int,
                    name0: String,
                    signature0: String?,
                    superName: String?,
                    interfaces0: Array<out String>?,
                ) {
                    classAccess = access
                    name = name0
                    signature = signature0
                    interfaces = interfaces0?.toList() ?: emptyList()
                }

                override fun visitOuterClass(
                    owner: String,
                    name: String?,
                    descriptor: String?,
                ) {
                    enclosingOwner = owner
                }

                override fun visitInnerClass(
                    name: String,
                    outerName: String?,
                    innerName: String?,
                    access: Int,
                ) {
                    innerClasses[name] = InnerEntry(outerName, innerName)
                }

                override fun visitAnnotation(
                    descriptor: String,
                    visible: Boolean,
                ): AnnotationVisitor? {
                    annotations += descriptor
                    if (!DEPRECATED_DESC.matches(descriptor)) return null
                    return object : AnnotationVisitor(Opcodes.ASM9) {
                        override fun visitEnum(
                            name: String?,
                            descriptor: String,
                            value: String,
                        ) {
                            if (name == "level" && DEPRECATION_LEVEL_DESC.matches(descriptor) && value == "HIDDEN") deprecatedHidden = true
                        }
                    }
                }

                override fun visitField(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    value: Any?,
                ): FieldVisitor? {
                    fields += FieldInfo(name, descriptor, access)
                    return null
                }

                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    if (!wantsCode(name)) {
                        methods += MethodInfo(name, descriptor, access, null)
                        return null
                    }
                    val insns = mutableListOf<Insn>()
                    methods += MethodInfo(name, descriptor, access, insns)
                    return InsnRecorder(insns)
                }
            }
        try {
            ClassReader(bytes).accept(visitor, ClassReader.SKIP_FRAMES or ClassReader.SKIP_DEBUG)
        } catch (_: RuntimeException) {
            return null
        }
        return Profile(
            classAccess,
            name,
            interfaces,
            signature,
            enclosingOwner,
            deprecatedHidden,
            annotations,
            innerClasses,
            fields,
            methods,
        )
    }

    private class InnerEntry(
        val outerName: String?,
        val innerName: String?,
    )

    private class FieldInfo(
        val name: String,
        val descriptor: String,
        val access: Int,
    )

    private class MethodInfo(
        val name: String,
        val descriptor: String,
        val access: Int,
        val insns: List<Insn>?,
    )

    private class Profile(
        val access: Int,
        val name: String,
        val interfaces: List<String>,
        val signature: String?,
        val enclosingOwner: String?,
        val deprecatedHidden: Boolean,
        val annotations: Set<String>,
        val innerClasses: Map<String, InnerEntry>,
        val fields: List<FieldInfo>,
        val methods: List<MethodInfo>,
    )

    /** One instruction a method body holds; [op] is -1 for a kind of instruction no shape here uses. */
    private class Insn(
        val op: Int,
        val owner: String = "",
        val name: String = "",
        val descriptor: String = "",
        val operand: Int = 0,
        val text: String? = null,
    )

    private class InsnRecorder(
        private val insns: MutableList<Insn>,
    ) : MethodVisitor(Opcodes.ASM9) {
        override fun visitInsn(opcode: Int) {
            insns += Insn(opcode)
        }

        override fun visitVarInsn(
            opcode: Int,
            varIndex: Int,
        ) {
            insns += Insn(opcode, operand = varIndex)
        }

        override fun visitIntInsn(
            opcode: Int,
            operand: Int,
        ) {
            insns += Insn(opcode, operand = operand)
        }

        override fun visitTypeInsn(
            opcode: Int,
            type: String,
        ) {
            insns += Insn(opcode, owner = type)
        }

        override fun visitFieldInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
        ) {
            insns += Insn(opcode, owner, name, descriptor)
        }

        override fun visitMethodInsn(
            opcodeAndSource: Int,
            owner: String,
            name: String,
            descriptor: String,
            isInterface: Boolean,
        ) {
            insns += Insn(opcodeAndSource, owner, name, descriptor)
        }

        override fun visitLdcInsn(value: Any?) {
            insns += Insn(Opcodes.LDC, text = value as? String)
        }

        override fun visitInvokeDynamicInsn(
            name: String,
            descriptor: String,
            bootstrapMethodHandle: Handle,
            vararg bootstrapMethodArguments: Any?,
        ) {
            insns += Insn(-1)
        }

        override fun visitJumpInsn(
            opcode: Int,
            label: Label,
        ) {
            insns += Insn(-1)
        }

        override fun visitIincInsn(
            varIndex: Int,
            increment: Int,
        ) {
            insns += Insn(-1)
        }

        override fun visitTableSwitchInsn(
            min: Int,
            max: Int,
            dflt: Label,
            vararg labels: Label,
        ) {
            insns += Insn(-1)
        }

        override fun visitLookupSwitchInsn(
            dflt: Label,
            keys: IntArray,
            labels: Array<out Label>,
        ) {
            insns += Insn(-1)
        }

        override fun visitMultiANewArrayInsn(
            descriptor: String,
            numDimensions: Int,
        ) {
            insns += Insn(-1)
        }
    }
}
