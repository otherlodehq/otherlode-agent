package dev.otherlode.instrumentation.branch

import dev.otherlode.export.GeneratedBy
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Shapes no compiler writes, built with ASM so each condition of the kotlinx.serialization rules is
 * pinned on its own: a lambda whose outer class is missing, does not run the lazy call with
 * `PUBLICATION` or lacks `@Serializable`, or stores it in an array another field takes; a
 * `$serializer` lookalike without one of its markers or with a getter the plugin does not name; a
 * companion its outer class holds under another name, or an object that is not serializable; and a
 * `@Serializer` object's `serialize` overload.
 */
class KotlinxSerializationForgedShapesTest {
    private val pkg = "com/example/target"
    private val outer = "$pkg/Outer"
    private val companion = "$pkg/Outer\$Companion"
    private val serializerClass = "$pkg/Outer\$\$serializer"
    private val lambda = "$pkg/Outer\$1"
    private val kSerializer = "kotlinx/serialization/KSerializer"
    private val kSerializerDesc = "L$kSerializer;"

    private fun analyze(
        bytes: ByteArray,
        served: Map<String, ByteArray>,
    ): BranchSiteAnalyzer.Analysis =
        BranchSiteAnalyzer.analyze(bytes, { served[it] }, includePackages = listOf("com.example.target")) { _, _ -> true }

    private fun ClassWriter.kotlinClass(
        name: String,
        superName: String = "java/lang/Object",
        interfaces: Array<String>? = null,
        serializable: Boolean = false,
        access: Int = Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER,
    ) {
        visit(Opcodes.V1_8, access, name, null, superName, interfaces)
        visitAnnotation("Lkotlin/Metadata;", true).visitEnd()
        if (serializable) visitAnnotation("Lkotlinx/serialization/Serializable;", true).visitEnd()
    }

    private fun ClassWriter.method(
        name: String,
        descriptor: String,
        access: Int = Opcodes.ACC_PUBLIC,
        body: MethodVisitor.() -> Unit,
    ) {
        visitMethod(access, name, descriptor, null, null).apply {
            visitCode()
            body()
            visitMaxs(0, 0)
            visitEnd()
        }
    }

    private fun lazyOf(
        visitor: MethodVisitor,
        lambdaName: String,
        mode: String = "PUBLICATION",
    ) = visitor.apply {
        visitFieldInsn(Opcodes.GETSTATIC, "kotlin/LazyThreadSafetyMode", mode, "Lkotlin/LazyThreadSafetyMode;")
        visitFieldInsn(Opcodes.GETSTATIC, lambdaName, "INSTANCE", "L$lambdaName;")
        visitTypeInsn(Opcodes.CHECKCAST, "kotlin/jvm/functions/Function0")
        visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "kotlin/LazyKt",
            "lazy",
            "(Lkotlin/LazyThreadSafetyMode;Lkotlin/jvm/functions/Function0;)Lkotlin/Lazy;",
            false,
        )
    }

    private fun lambdaBytes(enclosing: String?): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.kotlinClass(lambda, "kotlin/jvm/internal/Lambda")
        if (enclosing != null) writer.visitOuterClass(enclosing, null, null)
        writer.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "INSTANCE", "L$lambda;", null, null).visitEnd()
        writer.method("<init>", "()V", 0) { visitInsn(Opcodes.RETURN) }
        writer.method("invoke", "()$kSerializerDesc", Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL) {
            visitInsn(Opcodes.ACONST_NULL)
            visitInsn(Opcodes.ARETURN)
        }
        writer.method("invoke", "()Ljava/lang/Object;", Opcodes.ACC_PUBLIC or Opcodes.ACC_BRIDGE or Opcodes.ACC_SYNTHETIC) {
            visitInsn(Opcodes.ACONST_NULL)
            visitInsn(Opcodes.ARETURN)
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun lambdaOuterBytes(
        serializable: Boolean,
        storesLambda: String?,
        sink: String = "\$cachedSerializer\$delegate",
        mode: String = "PUBLICATION",
        intoArrayOf: String? = null,
    ): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.kotlinClass(outer, serializable = serializable)
        writer.visitField(Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, sink, "Lkotlin/Lazy;", null, null).visitEnd()
        writer.method("<clinit>", "()V", Opcodes.ACC_STATIC) {
            if (storesLambda != null && intoArrayOf != null) {
                visitInsn(Opcodes.ICONST_1)
                visitTypeInsn(Opcodes.ANEWARRAY, "kotlin/Lazy")
                visitInsn(Opcodes.DUP)
                visitInsn(Opcodes.ICONST_0)
                lazyOf(this, storesLambda, mode)
                visitInsn(Opcodes.AASTORE)
                visitFieldInsn(Opcodes.PUTSTATIC, outer, intoArrayOf, "[Lkotlin/Lazy;")
            } else if (storesLambda != null) {
                lazyOf(this, storesLambda, mode)
                visitFieldInsn(Opcodes.PUTSTATIC, outer, sink, "Lkotlin/Lazy;")
            }
            visitInsn(Opcodes.RETURN)
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun lambdaMarks(
        enclosing: String?,
        outerBytes: ByteArray?,
    ): List<GeneratedBy> {
        val bytes = lambdaBytes(enclosing)
        val analysis = analyze(bytes, if (outerBytes == null) emptyMap() else mapOf(outer to outerBytes))
        return listOf("<init>" to "()V", "invoke" to "()$kSerializerDesc", "invoke" to "()Ljava/lang/Object;").map {
            analysis.generatedBy(it.first, it.second)
        }
    }

    @Test
    fun `a lambda stored in the outer class's cached serializer delegate is marked`() {
        assertEquals(
            listOf(GeneratedBy.KOTLINX_SERIALIZATION, GeneratedBy.KOTLINX_SERIALIZATION, GeneratedBy.KOTLINX_SERIALIZATION),
            lambdaMarks(outer, lambdaOuterBytes(serializable = true, storesLambda = lambda)),
        )
    }

    @Test
    fun `a lambda whose outer class cannot be read is not marked`() {
        assertEquals(listOf(GeneratedBy.NONE, GeneratedBy.NONE, GeneratedBy.NONE), lambdaMarks(outer, null))
        assertEquals(listOf(GeneratedBy.NONE, GeneratedBy.NONE, GeneratedBy.NONE), lambdaMarks(null, lambdaOuterBytes(true, lambda)))
    }

    @Test
    fun `a lambda the outer class's type initializer does not hand to lazy is not marked`() {
        assertEquals(
            listOf(GeneratedBy.NONE, GeneratedBy.NONE, GeneratedBy.NONE),
            lambdaMarks(outer, lambdaOuterBytes(serializable = true, storesLambda = null)),
        )
        assertEquals(
            listOf(GeneratedBy.NONE, GeneratedBy.NONE, GeneratedBy.NONE),
            lambdaMarks(outer, lambdaOuterBytes(serializable = true, storesLambda = "$pkg/Outer\$2")),
        )
    }

    @Test
    fun `a lambda handed to lazy with another mode is not marked`() {
        assertEquals(
            listOf(GeneratedBy.NONE, GeneratedBy.NONE, GeneratedBy.NONE),
            lambdaMarks(outer, lambdaOuterBytes(serializable = true, storesLambda = lambda, mode = "SYNCHRONIZED")),
        )
    }

    @Test
    fun `a lambda stored in an array is marked only when the array becomes the child serializers`() {
        val marked = List(3) { GeneratedBy.KOTLINX_SERIALIZATION }
        assertEquals(
            marked,
            lambdaMarks(outer, lambdaOuterBytes(serializable = true, storesLambda = lambda, intoArrayOf = "\$childSerializers")),
        )
        assertEquals(
            listOf(GeneratedBy.NONE, GeneratedBy.NONE, GeneratedBy.NONE),
            lambdaMarks(outer, lambdaOuterBytes(serializable = true, storesLambda = lambda, intoArrayOf = "lazies")),
        )
    }

    @Test
    fun `a lambda stored in a field the plugin does not name, or in an outer class without Serializable, is not marked`() {
        assertEquals(
            listOf(GeneratedBy.NONE, GeneratedBy.NONE, GeneratedBy.NONE),
            lambdaMarks(outer, lambdaOuterBytes(serializable = true, storesLambda = lambda, sink = "other\$delegate")),
        )
        assertEquals(
            listOf(GeneratedBy.NONE, GeneratedBy.NONE, GeneratedBy.NONE),
            lambdaMarks(outer, lambdaOuterBytes(serializable = false, storesLambda = lambda)),
        )
    }

    private fun serializerClassBytes(
        withDeprecatedHidden: Boolean,
        withInstance: Boolean,
        withInnerEntry: Boolean = true,
        withGeneratedSerializer: Boolean = true,
        typeSerialField: String? = null,
    ): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        val interfaces =
            if (withGeneratedSerializer) {
                arrayOf(
                    "kotlinx/serialization/internal/GeneratedSerializer",
                )
            } else {
                arrayOf(kSerializer)
            }
        writer.kotlinClass(serializerClass, interfaces = interfaces)
        if (withDeprecatedHidden) {
            writer.visitAnnotation("Lkotlin/Deprecated;", true).apply {
                visitEnum("level", "Lkotlin/DeprecationLevel;", "HIDDEN")
                visitEnd()
            }
        }
        if (withInnerEntry) writer.visitInnerClass(serializerClass, outer, "\$serializer", Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL)
        if (withInstance) {
            writer
                .visitField(
                    Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
                    "INSTANCE",
                    "L$serializerClass;",
                    null,
                    null,
                ).visitEnd()
        }
        writer.method("getDescriptor", "()Lkotlinx/serialization/descriptors/SerialDescriptor;") {
            visitInsn(Opcodes.ACONST_NULL)
            visitInsn(Opcodes.ARETURN)
        }
        writer.method("extra", "()I") {
            visitInsn(Opcodes.ICONST_1)
            visitInsn(Opcodes.IRETURN)
        }
        if (typeSerialField !=
            null
        ) {
            writer.visitField(Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL, typeSerialField, kSerializerDesc, null, null).visitEnd()
        }
        writer.method("serialize", "(I)V") { visitInsn(Opcodes.RETURN) }
        writer.method("getTypeSerialFoo", "()$kSerializerDesc") {
            visitInsn(Opcodes.ACONST_NULL)
            visitInsn(Opcodes.ARETURN)
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun outerBytes(
        serializable: Boolean,
        companionField: String = "Companion",
        privateField: Boolean = false,
        staticField: Boolean = true,
        fieldDescriptor: String = "L$companion;",
    ): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.kotlinClass(outer, serializable = serializable)
        writer.visitInnerClass(companion, outer, "Companion", Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL)
        writer
            .visitField(
                (if (privateField) Opcodes.ACC_PRIVATE else Opcodes.ACC_PUBLIC) or (if (staticField) Opcodes.ACC_STATIC else 0) or
                    Opcodes.ACC_FINAL,
                companionField,
                fieldDescriptor,
                null,
                null,
            ).visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun serializerMark(
        bytes: ByteArray,
        outerSerializable: Boolean,
    ): Pair<GeneratedBy, GeneratedBy> {
        val analysis = analyze(bytes, mapOf(outer to outerBytes(outerSerializable)))
        return analysis.generatedBy("getDescriptor", "()Lkotlinx/serialization/descriptors/SerialDescriptor;") to
            analysis.generatedBy("extra", "()I")
    }

    @Test
    fun `a serializer class with every marker has the plugin's members marked and any other method not`() {
        val bytes = serializerClassBytes(withDeprecatedHidden = true, withInstance = true)
        assertEquals(GeneratedBy.KOTLINX_SERIALIZATION to GeneratedBy.NONE, serializerMark(bytes, outerSerializable = true))
        val analysis = analyze(bytes, mapOf(outer to outerBytes(serializable = true)))
        assertEquals(GeneratedBy.NONE, analysis.generatedBy("getTypeSerialFoo", "()$kSerializerDesc"))
        assertEquals(GeneratedBy.NONE, analysis.generatedBy("serialize", "(I)V"), "a serialize that takes no Encoder")
    }

    @Test
    fun `a serializer class missing one marker is not marked`() {
        val none = GeneratedBy.NONE to GeneratedBy.NONE

        assertEquals(
            none,
            serializerMark(serializerClassBytes(withDeprecatedHidden = false, withInstance = true), outerSerializable = true),
        )
        assertEquals(
            none,
            serializerMark(serializerClassBytes(withDeprecatedHidden = true, withInstance = false), outerSerializable = true),
        )
        assertEquals(
            none,
            serializerMark(serializerClassBytes(withDeprecatedHidden = true, withInstance = true), outerSerializable = false),
        )
        assertEquals(
            none,
            serializerMark(
                serializerClassBytes(withDeprecatedHidden = true, withInstance = true, withInnerEntry = false),
                outerSerializable = true,
            ),
        )
        assertEquals(
            none,
            serializerMark(
                serializerClassBytes(withDeprecatedHidden = true, withInstance = true, withGeneratedSerializer = false),
                outerSerializable = true,
            ),
        )
        assertEquals(
            GeneratedBy.KOTLINX_SERIALIZATION to GeneratedBy.NONE,
            serializerMark(serializerClassBytes(withDeprecatedHidden = true, withInstance = false, typeSerialField = "typeSerial0"), true),
        )
        assertEquals(
            none,
            serializerMark(serializerClassBytes(withDeprecatedHidden = true, withInstance = false, typeSerialField = "typeSerialX"), true),
        )
    }

    private fun companionBytes(
        extraInstruction: Boolean,
        companionConstructor: Boolean = true,
        innerOuter: String = outer,
        publicConstructor: Boolean = false,
        constructorAccess: Int = if (publicConstructor) Opcodes.ACC_PUBLIC else Opcodes.ACC_PRIVATE,
        classAccess: Int = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
        extraConstructor: Pair<String, Int>? = null,
        markerConstructor: Pair<String, Int> =
            "(Lkotlin/jvm/internal/DefaultConstructorMarker;)V" to
                (Opcodes.ACC_PUBLIC or Opcodes.ACC_SYNTHETIC),
    ): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.kotlinClass(companion, access = classAccess)
        writer.visitInnerClass(companion, innerOuter, "Companion", Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL)
        writer.method("<init>", "()V", constructorAccess) { visitInsn(Opcodes.RETURN) }
        if (companionConstructor) {
            writer.method("<init>", markerConstructor.first, markerConstructor.second) { visitInsn(Opcodes.RETURN) }
        }
        extraConstructor?.let { (descriptor, access) -> writer.method("<init>", descriptor, access) { visitInsn(Opcodes.RETURN) } }
        writer.method("serializer", "()$kSerializerDesc", Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL) {
            visitFieldInsn(Opcodes.GETSTATIC, serializerClass, "INSTANCE", "L$serializerClass;")
            if (extraInstruction) {
                visitInsn(Opcodes.DUP)
                visitInsn(Opcodes.POP)
            }
            visitTypeInsn(Opcodes.CHECKCAST, kSerializer)
            visitInsn(Opcodes.ARETURN)
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun companionMark(
        extraInstruction: Boolean,
        outerSerializable: Boolean,
        companionField: String = "Companion",
        companionConstructor: Boolean = true,
        innerOuter: String = outer,
        privateField: Boolean = false,
        publicConstructor: Boolean = false,
        staticField: Boolean = true,
        constructorAccess: Int = if (publicConstructor) Opcodes.ACC_PUBLIC else Opcodes.ACC_PRIVATE,
        classAccess: Int = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
        fieldDescriptor: String = "L$companion;",
        extraConstructor: Pair<String, Int>? = null,
        markerConstructor: Pair<String, Int> =
            "(Lkotlin/jvm/internal/DefaultConstructorMarker;)V" to
                (Opcodes.ACC_PUBLIC or Opcodes.ACC_SYNTHETIC),
    ) = analyze(
        companionBytes(
            extraInstruction,
            companionConstructor,
            innerOuter,
            publicConstructor,
            constructorAccess,
            classAccess,
            extraConstructor,
            markerConstructor,
        ),
        mapOf(outer to outerBytes(outerSerializable, companionField, privateField, staticField, fieldDescriptor)),
    ).generatedBy("serializer", "()$kSerializerDesc")

    @Test
    fun `a companion's serializer that reads the serializer class instance is marked only for a Serializable outer class`() {
        assertEquals(GeneratedBy.KOTLINX_SERIALIZATION, companionMark(extraInstruction = false, outerSerializable = true))
        assertEquals(GeneratedBy.NONE, companionMark(extraInstruction = false, outerSerializable = false))
    }

    @Test
    fun `a companion's serializer is marked whatever its body, since the plugin refuses one the adopter wrote`() {
        assertEquals(GeneratedBy.KOTLINX_SERIALIZATION, companionMark(extraInstruction = true, outerSerializable = true))
    }

    @Test
    fun `a nested class with a constructor beside the private and marker ones is no companion`() {
        listOf(
            "(I)V" to (Opcodes.ACC_PUBLIC or Opcodes.ACC_SYNTHETIC),
            "(I)V" to Opcodes.ACC_PRIVATE,
            "(Lkotlin/jvm/internal/DefaultConstructorMarker;I)V" to (Opcodes.ACC_PUBLIC or Opcodes.ACC_SYNTHETIC),
        ).forEach { extra ->
            assertEquals(
                GeneratedBy.NONE,
                companionMark(extraInstruction = false, outerSerializable = true, extraConstructor = extra),
                extra.first,
            )
        }
    }

    @Test
    fun `a deprecated marker constructor is the adopter's, and a marker under a relocated package is the compiler's`() {
        val synthetic = Opcodes.ACC_PUBLIC or Opcodes.ACC_SYNTHETIC
        assertEquals(
            GeneratedBy.NONE,
            companionMark(
                extraInstruction = false,
                outerSerializable = true,
                markerConstructor = "(Lkotlin/jvm/internal/DefaultConstructorMarker;)V" to (synthetic or Opcodes.ACC_DEPRECATED),
            ),
        )
        assertEquals(
            GeneratedBy.KOTLINX_SERIALIZATION,
            companionMark(
                extraInstruction = false,
                outerSerializable = true,
                markerConstructor = "(Lacme/shaded/kotlin/jvm/internal/DefaultConstructorMarker;)V" to synthetic,
            ),
        )
    }

    @Test
    fun `a nested class with a non-private constructor, in an instance field, or under another outer, is no companion`() {
        assertEquals(GeneratedBy.NONE, companionMark(extraInstruction = false, outerSerializable = true, publicConstructor = true))
        assertEquals(GeneratedBy.NONE, companionMark(extraInstruction = false, outerSerializable = true, staticField = false))
        assertEquals(GeneratedBy.NONE, companionMark(extraInstruction = false, outerSerializable = true, fieldDescriptor = "I"))
        assertEquals(
            GeneratedBy.NONE,
            companionMark(extraInstruction = false, outerSerializable = true, constructorAccess = Opcodes.ACC_PROTECTED),
        )
        assertEquals(GeneratedBy.NONE, companionMark(extraInstruction = false, outerSerializable = true, constructorAccess = 0))
        assertEquals(
            GeneratedBy.NONE,
            companionMark(extraInstruction = false, outerSerializable = true, classAccess = Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER),
        )
        assertEquals(
            GeneratedBy.NONE,
            companionMark(
                extraInstruction = false,
                outerSerializable = true,
                classAccess = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER or Opcodes.ACC_ENUM,
            ),
        )
        assertEquals(
            GeneratedBy.KOTLINX_SERIALIZATION,
            companionMark(extraInstruction = false, outerSerializable = true, privateField = true),
        )
        assertEquals(
            GeneratedBy.KOTLINX_SERIALIZATION,
            companionMark(extraInstruction = false, outerSerializable = true, companionConstructor = false),
        )
        assertEquals(GeneratedBy.NONE, companionMark(extraInstruction = false, outerSerializable = true, innerOuter = "$pkg/Elsewhere"))
    }

    @Test
    fun `a serializable class's own instance serializer method is not marked`() {
        val name = "$pkg/ForgedPlainSerializable"
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.kotlinClass(name, serializable = true)
        writer.method("serializer", "()$kSerializerDesc") {
            visitInsn(Opcodes.ACONST_NULL)
            visitInsn(Opcodes.ARETURN)
        }
        writer.visitEnd()

        assertEquals(GeneratedBy.NONE, analyze(writer.toByteArray(), emptyMap()).generatedBy("serializer", "()$kSerializerDesc"))
    }

    @Test
    fun `generatedSerializer is marked only beside KeepGeneratedSerializer and only when it reads the serializer class's INSTANCE`() {
        fun outerWith(keep: Boolean): ByteArray {
            val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
            writer.kotlinClass(outer, serializable = true)
            if (keep) writer.visitAnnotation("Lkotlinx/serialization/KeepGeneratedSerializer;", true).visitEnd()
            writer.visitInnerClass(companion, outer, "Companion", Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL)
            writer
                .visitField(
                    Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
                    "Companion",
                    "L$companion;",
                    null,
                    null,
                ).visitEnd()
            writer.visitEnd()
            return writer.toByteArray()
        }

        fun companionWith(readsFrom: String): ByteArray {
            val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
            writer.kotlinClass(companion, access = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER)
            writer.visitInnerClass(companion, outer, "Companion", Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL)
            writer.method("<init>", "()V", Opcodes.ACC_PRIVATE) { visitInsn(Opcodes.RETURN) }
            writer.method("<init>", "(Lkotlin/jvm/internal/DefaultConstructorMarker;)V", Opcodes.ACC_PUBLIC or Opcodes.ACC_SYNTHETIC) {
                visitInsn(Opcodes.RETURN)
            }
            writer.method("generatedSerializer", "()$kSerializerDesc", Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL) {
                visitFieldInsn(Opcodes.GETSTATIC, readsFrom, "INSTANCE", "L$readsFrom;")
                visitTypeInsn(Opcodes.CHECKCAST, kSerializer)
                visitInsn(Opcodes.ARETURN)
            }
            writer.visitEnd()
            return writer.toByteArray()
        }

        fun mark(
            keep: Boolean,
            readsFrom: String,
        ) = analyze(companionWith(readsFrom), mapOf(outer to outerWith(keep))).generatedBy("generatedSerializer", "()$kSerializerDesc")

        assertEquals(GeneratedBy.KOTLINX_SERIALIZATION, mark(keep = true, readsFrom = serializerClass))
        assertEquals(GeneratedBy.NONE, mark(keep = false, readsFrom = serializerClass))
        assertEquals(GeneratedBy.NONE, mark(keep = true, readsFrom = "$pkg/Other"))
    }

    @Test
    fun `a nested class the outer class holds under another field name is no companion`() {
        assertEquals(GeneratedBy.NONE, companionMark(extraInstruction = false, outerSerializable = true, companionField = "helper"))
    }

    @Test
    fun `a Serializer object's serialize overload with another value is not an unread shape`() {
        val name = "$pkg/ForgedObjectSerializer"
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.kotlinClass(name, interfaces = arrayOf(kSerializer))
        writer.visitAnnotation("Lkotlinx/serialization/Serializer;", false).visitEnd()
        val encoder = "Lkotlinx/serialization/encoding/Encoder;"
        writer.method("serialize", "(${encoder}Ljava/lang/String;)V") { visitInsn(Opcodes.RETURN) }
        writer.method("serialize", "(${encoder}Ljava/lang/String;Z)V") { visitInsn(Opcodes.RETURN) }
        writer.visitEnd()
        val analysis = analyze(writer.toByteArray(), emptyMap())

        assertEquals(
            dev.otherlode.export.UnreadShape.KOTLINX_SERIALIZER_OBJECT,
            analysis.unreadShape("serialize", "(${encoder}Ljava/lang/String;)V"),
        )
        assertEquals(dev.otherlode.export.UnreadShape.NONE, analysis.unreadShape("serialize", "(${encoder}Ljava/lang/String;Z)V"))
    }
}
