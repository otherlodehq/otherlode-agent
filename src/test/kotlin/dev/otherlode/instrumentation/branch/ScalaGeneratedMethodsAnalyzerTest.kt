package dev.otherlode.instrumentation.branch

import dev.otherlode.export.CallEdge
import dev.otherlode.export.GeneratedBy
import dev.otherlode.export.UnreadShape
import dev.otherlode.instrumentation.CompilerFixtures
import net.bytebuddy.jar.asm.Attribute
import net.bytebuddy.jar.asm.ByteVector
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Handle
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves the Scala generated-method rules at the analyser, over the Scala fixture modules' own class bytes and over
 * classes built here with ASM: a call into a static forwarder passes through to the object's
 * method, and the forwarder shape counts only in a class scalac compiled.
 */
class ScalaGeneratedMethodsAnalyzerTest {
    companion object {
        /** The 3.3.4 baseline, named `baseline`, and every Scala 3 release in the matrix. */
        @JvmStatic
        fun scala3Builds(): List<String> = listOf("baseline") + CompilerFixtures.scalacVersions.filter { it.startsWith("3.") }

        private const val PACKAGE = "com/example/scalatarget"
    }

    /** Reads a class of the fixture module [module] by internal name, as the agent's own lookup would. */
    private fun fixtureLookup(module: String): (String) -> ByteArray? =
        { internalName ->
            File(System.getProperty("otherlode.fixtures.$module.dir"), "$internalName.class").takeIf { it.isFile }?.readBytes()
        }

    private fun analyze(
        classBytes: ByteArray,
        lookup: (String) -> ByteArray? = { null },
    ): BranchSiteAnalyzer.Analysis =
        BranchSiteAnalyzer.analyze(classBytes, lookup, listOf("com.example.scalatarget")) { name, _ -> name != "<clinit>" }

    /** A class attribute named [name] with an empty body, such as the `Scala` attribute scalac writes. */
    private class ScalaAttribute(
        name: String = "Scala",
    ) : Attribute(name) {
        override fun write(
            classWriter: ClassWriter?,
            code: ByteArray?,
            codeLength: Int,
            maxStack: Int,
            maxLocals: Int,
        ): ByteVector = ByteVector()
    }

    /**
     * A class named [internalName] with one static `m(IJ)I` whose body reads `<moduleOwner>.MODULE$`,
     * loads both parameters and calls `m(IJ)I` on it, the shape scalac gives a static forwarder.
     * With [scala] the class carries a `Scala` attribute.
     */
    private fun forwarderShaped(
        internalName: String,
        moduleOwner: String,
        scala: Boolean,
    ): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, internalName, null, "java/lang/Object", null)
        if (scala) writer.visitAttribute(ScalaAttribute())
        val method = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "m", "(IJ)I", null, null)
        method.visitCode()
        method.visitFieldInsn(Opcodes.GETSTATIC, moduleOwner, "MODULE\$", "L$moduleOwner;")
        method.visitVarInsn(Opcodes.ILOAD, 0)
        method.visitVarInsn(Opcodes.LLOAD, 1)
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, moduleOwner, "m", "(IJ)I", false)
        method.visitInsn(Opcodes.IRETURN)
        method.visitMaxs(0, 0)
        method.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    /** A class with one static method per entry of [calls], each calling that static method and returning its result. */
    private fun caller(vararg calls: Triple<String, String, String>): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "$PACKAGE/ForwarderCaller", null, "java/lang/Object", null)
        calls.forEachIndexed { index, (owner, name, descriptor) ->
            val method = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "call$index", "()Ljava/lang/Object;", null, null)
            method.visitCode()
            if (descriptor.startsWith("(II)")) {
                method.visitInsn(Opcodes.ICONST_1)
                method.visitInsn(Opcodes.ICONST_2)
            }
            method.visitMethodInsn(Opcodes.INVOKESTATIC, owner, name, descriptor, false)
            if (descriptor.endsWith(")I")) method.visitInsn(Opcodes.POP).also { method.visitInsn(Opcodes.ACONST_NULL) }
            method.visitInsn(Opcodes.ARETURN)
            method.visitMaxs(0, 0)
            method.visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun `a call into a static forwarder reaches the object's method`(module: String) {
        val analysis =
            analyze(
                caller(
                    Triple("$PACKAGE/Driver", "callSimpleAllOmitted", "()I"),
                    Triple("$PACKAGE/Cc", "apply", "(II)L$PACKAGE/Cc;"),
                ),
                fixtureLookup(module),
            )

        assertEquals(
            listOf(
                CallEdge("com.example.scalatarget.Driver\$", "<clinit>", "()V", virtual = false),
                CallEdge("com.example.scalatarget.Driver\$", "callSimpleAllOmitted", "()I", virtual = true),
                CallEdge("com.example.scalatarget.Driver", "<clinit>", "()V", virtual = false),
            ),
            analysis.callsOf("call0", "()Ljava/lang/Object;"),
        )
        assertEquals(
            listOf(
                CallEdge("com.example.scalatarget.Cc\$", "<clinit>", "()V", virtual = false),
                CallEdge("com.example.scalatarget.Cc\$", "apply", "(II)Lcom/example/scalatarget/Cc;", virtual = true),
                CallEdge("com.example.scalatarget.Cc", "<clinit>", "()V", virtual = false),
            ),
            analysis.callsOf("call1", "()Ljava/lang/Object;"),
        )
    }

    @Test
    fun `scala 3 - a call into a static forwarder reaches the object's method`() =
        `a call into a static forwarder reaches the object's method`("scala3")

    @Test
    fun `scala 2 - a call into a static forwarder reaches the object's method`() =
        `a call into a static forwarder reaches the object's method`("scala2")

    @Test
    fun `the forwarder shape is a static forwarder only in a class carrying a Scala attribute`() {
        val scala = analyze(forwarderShaped("$PACKAGE/Shape", "$PACKAGE/Shape\$", scala = true))
        val java = analyze(forwarderShaped("$PACKAGE/Shape", "$PACKAGE/Shape\$", scala = false))

        assertEquals(GeneratedBy.STATIC_FORWARDER, scala.generatedBy("m", "(IJ)I"))
        assertEquals(GeneratedBy.NONE, java.generatedBy("m", "(IJ)I"))
    }

    @Test
    fun `a forwarder to an object other than the class's own twin is not a static forwarder`() {
        val analysis = analyze(forwarderShaped("$PACKAGE/Shape", "$PACKAGE/Other\$", scala = true))

        assertEquals(GeneratedBy.NONE, analysis.generatedBy("m", "(IJ)I"))
    }

    private fun `a companion whose partner cannot be read marks no plumbing`(module: String) {
        val companion = fixtureLookup(module)("$PACKAGE/Cc\$")!!

        val analysis = analyze(companion)

        assertEquals(GeneratedBy.NONE, analysis.generatedBy("apply", "(II)L$PACKAGE/Cc;"))
        assertEquals(GeneratedBy.NONE, analysis.generatedBy("toString", "()Ljava/lang/String;"))
        assertEquals(GeneratedBy.SCALA_OBJECT, analysis.generatedBy("writeReplace", "()Ljava/lang/Object;"))
    }

    @Test
    fun `scala 3 - a companion whose partner cannot be read marks no plumbing`() =
        `a companion whose partner cannot be read marks no plumbing`("scala3")

    @Test
    fun `scala 2 - a companion whose partner cannot be read marks no plumbing`() =
        `a companion whose partner cannot be read marks no plumbing`("scala2")

    private fun `a module class reads its partner only when it declares a companion candidate`(module: String) {
        val lookup = fixtureLookup(module)
        val asked = mutableListOf<String>()
        val recording = { internalName: String -> lookup(internalName).also { asked += internalName } }

        ScalaGeneratedMethods.of(lookup("$PACKAGE/Driver\$")!!, recording)
        assertEquals(emptyList(), asked, "Driver\$ declares no apply, unapply, toString or fromProduct for a partner")

        ScalaGeneratedMethods.of(lookup("$PACKAGE/Cc\$")!!, recording)
        assertEquals(listOf("$PACKAGE/Cc"), asked)
    }

    @Test
    fun `scala 3 - a module class reads its partner only when it declares a companion candidate`() =
        `a module class reads its partner only when it declares a companion candidate`("scala3")

    @Test
    fun `scala 2 - a module class reads its partner only when it declares a companion candidate`() =
        `a module class reads its partner only when it declares a companion candidate`("scala2")

    private fun `a case class with an auxiliary constructor marks its plumbing and not its constructors`(module: String) {
        val written = fixtureLookup(module)("$PACKAGE/Written")!!

        val analysis = analyze(written)

        assertEquals(GeneratedBy.CASE_CLASS, analysis.generatedBy("canEqual", "(Ljava/lang/Object;)Z"))
        assertEquals(GeneratedBy.NONE, analysis.generatedBy("<init>", "(Ljava/lang/String;)V"))
        assertEquals(GeneratedBy.NONE, analysis.generatedBy("<init>", "(ILjava/lang/String;)V"))
    }

    @Test
    fun `scala 3 - a case class with an auxiliary constructor marks its plumbing and not its constructors`() =
        `a case class with an auxiliary constructor marks its plumbing and not its constructors`("scala3")

    @Test
    fun `scala 2 - a case class with an auxiliary constructor marks its plumbing and not its constructors`() =
        `a case class with an auxiliary constructor marks its plumbing and not its constructors`("scala2")

    /**
     * [classBytes] with a `nop` added to every method named in [names], at the start or, with
     * [beforeLast], just before the method's last instruction.
     */
    private fun withNop(
        classBytes: ByteArray,
        names: Set<String>,
        beforeLast: Boolean = false,
    ): ByteArray {
        val counts = mutableMapOf<String, Int>()
        if (beforeLast) {
            val counter =
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitMethod(
                        access: Int,
                        name: String,
                        descriptor: String,
                        signature: String?,
                        exceptions: Array<out String>?,
                    ): MethodVisitor = InstructionHook(null) { counts.merge(name + descriptor, 1, Int::plus) }
                }
            ClassReader(classBytes).accept(counter, 0)
        }
        val writer = ClassWriter(0)
        val visitor =
            object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor {
                    val delegate = super.visitMethod(access, name, descriptor, signature, exceptions)
                    if (name !in names) return delegate
                    if (beforeLast) {
                        val last = counts.getValue(name + descriptor) - 1
                        var seen = 0
                        return InstructionHook(delegate) { if (seen++ == last) delegate.visitInsn(Opcodes.NOP) }
                    }
                    return object : MethodVisitor(Opcodes.ASM9, delegate) {
                        override fun visitCode() {
                            super.visitCode()
                            super.visitInsn(Opcodes.NOP)
                        }
                    }
                }
            }
        ClassReader(classBytes).accept(visitor, 0)
        return writer.toByteArray()
    }

    /** Passes every call to [delegate], running [beforeInstruction] ahead of each instruction. */
    private class InstructionHook(
        delegate: MethodVisitor?,
        private val beforeInstruction: () -> Unit,
    ) : MethodVisitor(Opcodes.ASM9, delegate) {
        override fun visitInsn(opcode: Int) {
            beforeInstruction()
            super.visitInsn(opcode)
        }

        override fun visitIntInsn(
            opcode: Int,
            operand: Int,
        ) {
            beforeInstruction()
            super.visitIntInsn(opcode, operand)
        }

        override fun visitVarInsn(
            opcode: Int,
            varIndex: Int,
        ) {
            beforeInstruction()
            super.visitVarInsn(opcode, varIndex)
        }

        override fun visitTypeInsn(
            opcode: Int,
            type: String,
        ) {
            beforeInstruction()
            super.visitTypeInsn(opcode, type)
        }

        override fun visitFieldInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
        ) {
            beforeInstruction()
            super.visitFieldInsn(opcode, owner, name, descriptor)
        }

        override fun visitMethodInsn(
            opcode: Int,
            owner: String,
            name: String,
            descriptor: String,
            isInterface: Boolean,
        ) {
            beforeInstruction()
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
        }

        override fun visitInvokeDynamicInsn(
            name: String,
            descriptor: String,
            bootstrapMethodHandle: Handle,
            vararg bootstrapMethodArguments: Any?,
        ) {
            beforeInstruction()
            super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, *bootstrapMethodArguments)
        }

        override fun visitJumpInsn(
            opcode: Int,
            label: Label,
        ) {
            beforeInstruction()
            super.visitJumpInsn(opcode, label)
        }

        override fun visitLdcInsn(value: Any?) {
            beforeInstruction()
            super.visitLdcInsn(value)
        }

        override fun visitIincInsn(
            varIndex: Int,
            increment: Int,
        ) {
            beforeInstruction()
            super.visitIincInsn(varIndex, increment)
        }

        override fun visitTableSwitchInsn(
            min: Int,
            max: Int,
            dflt: Label,
            vararg labels: Label,
        ) {
            beforeInstruction()
            super.visitTableSwitchInsn(min, max, dflt, *labels)
        }

        override fun visitLookupSwitchInsn(
            dflt: Label,
            keys: IntArray,
            labels: Array<out Label>,
        ) {
            beforeInstruction()
            super.visitLookupSwitchInsn(dflt, keys, labels)
        }

        override fun visitMultiANewArrayInsn(
            descriptor: String,
            numDimensions: Int,
        ) {
            beforeInstruction()
            super.visitMultiANewArrayInsn(descriptor, numDimensions)
        }
    }

    private fun `one instruction outside the shape leaves a method unmarked`(module: String) {
        val lookup = fixtureLookup(module)
        val mixed = lookup("$PACKAGE/Mixed")!!
        val changed = setOf("equals", "hashCode", "productElement", "copy")

        val original = analyze(mixed, lookup)
        val withExtra = analyze(withNop(mixed, changed), lookup)
        val withExtraAtEnd = analyze(withNop(mixed, changed, beforeLast = true), lookup)

        val descriptors =
            mapOf(
                "equals" to "(Ljava/lang/Object;)Z",
                "hashCode" to "()I",
                "productElement" to "(I)Ljava/lang/Object;",
                "copy" to "(JDZLjava/lang/String;IFCBSLjava/lang/Object;Lscala/Option;)L$PACKAGE/Mixed;",
            )
        for ((name, descriptor) in descriptors) {
            assertEquals(GeneratedBy.CASE_CLASS, original.generatedBy(name, descriptor), name)
            assertEquals(GeneratedBy.NONE, withExtra.generatedBy(name, descriptor), "$name with a nop")
            assertEquals(GeneratedBy.NONE, withExtraAtEnd.generatedBy(name, descriptor), "$name with a nop before its last instruction")
        }
        assertEquals(GeneratedBy.CASE_CLASS, withExtra.generatedBy("canEqual", "(Ljava/lang/Object;)Z"))
        assertEquals(GeneratedBy.CASE_CLASS, withExtra.generatedBy("productElementName", "(I)Ljava/lang/String;"))
        assertEquals(GeneratedBy.CASE_CLASS, withExtraAtEnd.generatedBy("canEqual", "(Ljava/lang/Object;)Z"))
    }

    @Test
    fun `scala 3 - one instruction outside the shape leaves a method unmarked`() =
        `one instruction outside the shape leaves a method unmarked`("scala3")

    @Test
    fun `scala 2 - one instruction outside the shape leaves a method unmarked`() =
        `one instruction outside the shape leaves a method unmarked`("scala2")

    private fun `an equals of scalac's shape comparing only some elements is not marked`(module: String) {
        val lookup = fixtureLookup(module)

        val analysis = analyze(lookup("$PACKAGE/E3")!!, lookup)

        assertEquals(GeneratedBy.NONE, analysis.generatedBy("equals", "(Ljava/lang/Object;)Z"))
        assertEquals(GeneratedBy.CASE_CLASS, analysis.generatedBy("hashCode", "()I"))
    }

    @Test
    fun `scala 3 - an equals of scalac's shape comparing only some elements is not marked`() =
        `an equals of scalac's shape comparing only some elements is not marked`("scala3")

    @Test
    fun `scala 2 - an equals of scalac's shape comparing only some elements is not marked`() =
        `an equals of scalac's shape comparing only some elements is not marked`("scala2")

    /**
     * [classBytes] with its Scala attributes swapped for the ones the other Scala version writes
     * on an inner class: `Scala` alone for Scala 3.3.4 ([asScala3]), `Scala` and
     * `ScalaInlineInfo` for Scala 2.13.15.
     */
    private fun relabelled(
        classBytes: ByteArray,
        asScala3: Boolean,
    ): ByteArray {
        val writer = ClassWriter(0)
        val visitor =
            object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visit(
                    version: Int,
                    access: Int,
                    name: String,
                    signature: String?,
                    superName: String?,
                    interfaces: Array<out String>?,
                ) {
                    super.visit(version, access, name, signature, superName, interfaces)
                    super.visitAttribute(ScalaAttribute())
                    if (!asScala3) super.visitAttribute(ScalaAttribute("ScalaInlineInfo"))
                }

                override fun visitAttribute(attribute: Attribute) {
                    if (attribute.type !in setOf("Scala", "ScalaSig", "ScalaInlineInfo", "TASTY")) super.visitAttribute(attribute)
                }
            }
        ClassReader(classBytes).accept(visitor, 0)
        return writer.toByteArray()
    }

    private fun `a shape only the other Scala version writes is not marked`(module: String) {
        val lookup = fixtureLookup(module)
        val asScala3 = module == "scala2"

        val mixed = analyze(relabelled(lookup("$PACKAGE/Mixed")!!, asScala3), lookup)
        for ((name, descriptor) in listOf(
            "equals" to "(Ljava/lang/Object;)Z",
            "productElement" to "(I)Ljava/lang/Object;",
            "productElementName" to "(I)Ljava/lang/String;",
            "productIterator" to "()Lscala/collection/Iterator;",
        )) {
            assertEquals(GeneratedBy.NONE, mixed.generatedBy(name, descriptor), "$module Mixed.$name read as the other version")
        }
        for ((name, descriptor) in listOf(
            "canEqual" to "(Ljava/lang/Object;)Z",
            "productPrefix" to "()Ljava/lang/String;",
            "hashCode" to "()I",
        )) {
            assertEquals(GeneratedBy.CASE_CLASS, mixed.generatedBy(name, descriptor), "$module Mixed.$name is the same in both versions")
        }

        val companion = analyze(relabelled(lookup("$PACKAGE/Cc\$")!!, asScala3), lookup)
        val unapply = if (module == "scala3") "(L$PACKAGE/Cc;)L$PACKAGE/Cc;" else "(L$PACKAGE/Cc;)Lscala/Option;"
        assertEquals(GeneratedBy.NONE, companion.generatedBy("unapply", unapply))
        assertEquals(GeneratedBy.CASE_CLASS, companion.generatedBy("apply", "(II)L$PACKAGE/Cc;"))
        if (module == "scala3") assertEquals(GeneratedBy.NONE, companion.generatedBy("fromProduct", "(Lscala/Product;)L$PACKAGE/Cc;"))

        val empty = analyze(relabelled(lookup("$PACKAGE/Empty\$")!!, asScala3), lookup)
        assertEquals(GeneratedBy.NONE, empty.generatedBy("unapply", "(L$PACKAGE/Empty;)Z"))
    }

    @Test
    fun `scala 3 - a shape only the other Scala version writes is not marked`() =
        `a shape only the other Scala version writes is not marked`("scala3")

    @Test
    fun `scala 2 - a shape only the other Scala version writes is not marked`() =
        `a shape only the other Scala version writes is not marked`("scala2")

    /**
     * The variants later and earlier scalac releases write (the matcher's KDoc names each). For
     * every one, the method is marked as the release wrote it, and is not once an instruction is
     * added at its start or just before its last instruction, so the variant is read exactly.
     */
    private fun assertVariantReadExactly(
        version: String,
        simpleName: String,
        names: Set<String>,
        expected: GeneratedBy,
        methods: Map<String, String>,
    ) {
        val build = CompilerFixtures.scalac(version)
        val bytes = build.classBytes(simpleName)
        val original = analyze(bytes, build.lookup)
        val withExtra = analyze(withNop(bytes, names), build.lookup)
        val withExtraAtEnd = analyze(withNop(bytes, names, beforeLast = true), build.lookup)
        for ((name, descriptor) in methods) {
            val label = "scalac $version $simpleName.$name"
            assertEquals(expected, original.generatedBy(name, descriptor), label)
            assertEquals(GeneratedBy.NONE, withExtra.generatedBy(name, descriptor), "$label with a nop")
            assertEquals(GeneratedBy.NONE, withExtraAtEnd.generatedBy(name, descriptor), "$label with a nop before its last instruction")
        }
    }

    @Test
    fun `scala 2_12 - hashCode without the prefix mix, equals in source order and an out-of-range Integer_toString are read exactly`() {
        assertVariantReadExactly(
            "2.12.20",
            "Mixed",
            setOf("hashCode", "equals", "productElement"),
            GeneratedBy.CASE_CLASS,
            mapOf(
                "hashCode" to "()I",
                "equals" to "(Ljava/lang/Object;)Z",
                "productElement" to "(I)Ljava/lang/Object;",
            ),
        )
        assertVariantReadExactly(
            "2.12.20",
            "Empty",
            setOf("productElement"),
            GeneratedBy.CASE_CLASS,
            mapOf("productElement" to "(I)Ljava/lang/Object;"),
        )
    }

    /** [classBytes] with one more instance method, [name] [descriptor], whose body returns null. */
    private fun withMethod(
        classBytes: ByteArray,
        name: String,
        descriptor: String,
        access: Int = Opcodes.ACC_PUBLIC,
    ): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        val visitor =
            object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visitEnd() {
                    val mv = super.visitMethod(access, name, descriptor, null, null)
                    mv.visitCode()
                    mv.visitInsn(Opcodes.ACONST_NULL)
                    mv.visitInsn(Opcodes.ARETURN)
                    mv.visitMaxs(0, 0)
                    mv.visitEnd()
                    super.visitEnd()
                }
            }
        ClassReader(classBytes).accept(visitor, 0)
        return writer.toByteArray()
    }

    /** [classBytes] with the private `writeReplace` scalac 2.13 writes for the module class [self]. */
    private fun withScalacWriteReplace(
        classBytes: ByteArray,
        self: String,
    ): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        val visitor =
            object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visitEnd() {
                    val proxy = "scala/runtime/ModuleSerializationProxy"
                    val mv = super.visitMethod(Opcodes.ACC_PRIVATE, "writeReplace", "()Ljava/lang/Object;", null, null)
                    mv.visitCode()
                    mv.visitTypeInsn(Opcodes.NEW, proxy)
                    mv.visitInsn(Opcodes.DUP)
                    mv.visitLdcInsn(
                        net.bytebuddy.jar.asm.Type
                            .getObjectType(self),
                    )
                    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, proxy, "<init>", "(Ljava/lang/Class;)V", false)
                    mv.visitInsn(Opcodes.ARETURN)
                    mv.visitMaxs(0, 0)
                    mv.visitEnd()
                    super.visitEnd()
                }
            }
        ClassReader(classBytes).accept(visitor, 0)
        return writer.toByteArray()
    }

    @Test
    fun `scala 2_12's own shapes are not read in a class 2_12 cannot have written`() {
        val build = CompilerFixtures.scalac("2.12.20")
        val shapes =
            mapOf(
                "hashCode" to "()I",
                "equals" to "(Ljava/lang/Object;)Z",
                "productElement" to "(I)Ljava/lang/Object;",
            )

        // Read as Scala 3, which never leaves the prefix mix out or writes these.
        val asScala3 = analyze(relabelled(build.classBytes("Mixed"), asScala3 = true), build.lookup)
        // Read as Scala 2.13, which gives every case class a productElementName.
        val as213 = analyze(withMethod(build.classBytes("Mixed"), "productElementName", "(I)Ljava/lang/String;"), build.lookup)
        for ((name, descriptor) in shapes) {
            assertEquals(GeneratedBy.NONE, asScala3.generatedBy(name, descriptor), "Mixed.$name read as Scala 3")
            assertEquals(GeneratedBy.NONE, as213.generatedBy(name, descriptor), "Mixed.$name in a class with productElementName")
        }

        val readResolve = "readResolve" to "()Ljava/lang/Object;"
        val companionAsScala3 = analyze(relabelled(build.classBytes("Cc\$"), asScala3 = true), build.lookup)
        val companionWithWriteReplace = analyze(withScalacWriteReplace(build.classBytes("Cc\$"), "$PACKAGE/Cc\$"), build.lookup)
        assertEquals(GeneratedBy.NONE, companionAsScala3.generatedBy(readResolve.first, readResolve.second), "readResolve read as Scala 3")
        assertEquals(
            GeneratedBy.NONE,
            companionWithWriteReplace.generatedBy(readResolve.first, readResolve.second),
            "readResolve beside scalac's own writeReplace, which only 2.13 writes",
        )
    }

    @Test
    fun `scala 2_12's out-of-range wording is not read in productElementName, which 2_12 never writes`() {
        val build = CompilerFixtures.scalac("2.12.20")
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        val visitor =
            object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    // productElement's body, renamed to productElementName with the String return:
                    // only the 2.12 out-of-range wording, nothing else that a String return changes.
                    if (name != "productElement") return super.visitMethod(access, name, descriptor, signature, exceptions)
                    return super.visitMethod(access, "productElementName", "(I)Ljava/lang/String;", null, exceptions)
                }
            }
        ClassReader(build.classBytes("Empty")).accept(visitor, 0)

        val analysis = analyze(writer.toByteArray(), build.lookup)
        assertEquals(GeneratedBy.NONE, analysis.generatedBy("productElementName", "(I)Ljava/lang/String;"))
    }

    @Test
    fun `scala 2_12 - a module class's readResolve is read exactly`() {
        assertVariantReadExactly(
            "2.12.20",
            "Cc\$",
            setOf("readResolve"),
            GeneratedBy.SCALA_OBJECT,
            mapOf("readResolve" to "()Ljava/lang/Object;"),
        )
    }

    @Test
    fun `scala 2_13_17 and later, and scala 3_3_7 and later - the folded prefix hash is read exactly`() {
        for (version in listOf("2.13.18", "3.3.7", "3.9.0")) {
            assertVariantReadExactly(version, "Cc", setOf("hashCode"), GeneratedBy.CASE_CLASS, mapOf("hashCode" to "()I"))
            assertVariantReadExactly(version, "One", setOf("hashCode"), GeneratedBy.CASE_CLASS, mapOf("hashCode" to "()I"))
            assertVariantReadExactly(version, "Empty", setOf("hashCode"), GeneratedBy.CASE_CLASS, mapOf("hashCode" to "()I"))
        }
    }

    /** [classBytes] with every `ldc` of the int [from] in method [method] changed to [to]. */
    private fun withConstant(
        classBytes: ByteArray,
        method: String,
        from: Int,
        to: Int,
    ): ByteArray {
        val writer = ClassWriter(0)
        val visitor =
            object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor {
                    val delegate = super.visitMethod(access, name, descriptor, signature, exceptions)
                    if (name != method) return delegate
                    return object : MethodVisitor(Opcodes.ASM9, delegate) {
                        override fun visitLdcInsn(value: Any?) = super.visitLdcInsn(if (value == from) to else value)
                    }
                }
            }
        ClassReader(classBytes).accept(visitor, 0)
        return writer.toByteArray()
    }

    @Test
    fun `a folded hashCode constant other than the class name's hash is not marked`() {
        val build = CompilerFixtures.scalac("2.13.18")
        val empty = build.classBytes("Empty")
        val one = build.classBytes("One")

        assertEquals(GeneratedBy.CASE_CLASS, analyze(empty, build.lookup).generatedBy("hashCode", "()I"))
        assertEquals(
            GeneratedBy.NONE,
            analyze(withConstant(empty, "hashCode", 67081517, 67081518), build.lookup).generatedBy("hashCode", "()I"),
        )
        assertEquals(
            GeneratedBy.NONE,
            analyze(withConstant(one, "hashCode", 741166282, 741166283), build.lookup).generatedBy("hashCode", "()I"),
        )
    }

    @Test
    fun `scala 3_3_8, 3_8_4 and later - an equals through Objects_equals is read exactly`() {
        for (version in listOf("3.3.8", "3.8.4", "3.9.0")) {
            assertVariantReadExactly(
                version,
                "Mixed",
                setOf("equals"),
                GeneratedBy.CASE_CLASS,
                mapOf("equals" to "(Ljava/lang/Object;)Z"),
            )
        }
    }

    @Test
    fun `scala 3_7_3 to 3_8_3 - an equals through a copy of the cast instance is read exactly`() {
        for (simpleName in listOf("Mixed", "Empty", "Outer\$Inner")) {
            assertVariantReadExactly(
                "3.8.3",
                simpleName,
                setOf("equals"),
                GeneratedBy.CASE_CLASS,
                mapOf("equals" to "(Ljava/lang/Object;)Z"),
            )
        }
    }

    @Test
    fun `scala 3_7_0 and later - a fromProduct that reads the elements into locals first is read exactly`() {
        for (version in listOf("3.7.0", "3.9.0")) {
            assertVariantReadExactly(
                version,
                "Cc\$",
                setOf("fromProduct"),
                GeneratedBy.CASE_CLASS,
                mapOf("fromProduct" to "(Lscala/Product;)L$PACKAGE/Cc;"),
            )
            assertVariantReadExactly(
                version,
                "Mixed\$",
                setOf("fromProduct"),
                GeneratedBy.CASE_CLASS,
                mapOf("fromProduct" to "(Lscala/Product;)L$PACKAGE/Mixed;"),
            )
            assertVariantReadExactly(
                version,
                "Outer\$Inner\$",
                setOf("fromProduct"),
                GeneratedBy.CASE_CLASS,
                mapOf("fromProduct" to "(Lscala/Product;)L$PACKAGE/Outer\$Inner;"),
            )
        }
    }

    @Test
    fun `scala 3_9_0 - an out-of-range index passed to IndexOutOfBoundsException_init is read exactly`() {
        assertVariantReadExactly(
            "3.9.0",
            "Cc",
            setOf("productElement", "productElementName"),
            GeneratedBy.CASE_CLASS,
            mapOf("productElement" to "(I)Ljava/lang/Object;", "productElementName" to "(I)Ljava/lang/String;"),
        )
        assertVariantReadExactly(
            "3.9.0",
            "Empty",
            setOf("productElement"),
            GeneratedBy.CASE_CLASS,
            mapOf("productElement" to "(I)Ljava/lang/Object;"),
        )
    }

    private val scala3Versions: List<String> get() = CompilerFixtures.scalacVersions.filter { it.startsWith("3.") }

    /**
     * Every method of [simpleName] named in [methods] (name and descriptor) is [GeneratedBy.ENUM] as
     * scalac [version] wrote it, and is [GeneratedBy.NONE] once one instruction is added at its start
     * or just before its last instruction. A `hashCode` is expected only where the build has one.
     */
    private fun assertEnumReadExactly(
        version: String,
        simpleName: String,
        methods: List<Pair<String, String>>,
    ) {
        val build = if (version == "3.3.4") CompilerFixtures.scalaBaseline(version) else CompilerFixtures.scalac(version)
        val declared = build.markEntries(simpleName).map { it.first to it.second }.toSet()
        val expected = methods.filter { it in declared }
        assertEquals(
            methods.filter { it.first != "hashCode" }.size,
            expected.count { it.first != "hashCode" },
            "scalac $version $simpleName",
        )
        val bytes = build.classBytes(simpleName)
        val names = expected.map { it.first }.toSet()
        val original = analyze(bytes, build.lookup)
        val withExtra = analyze(withNop(bytes, names), build.lookup)
        val withExtraAtEnd = analyze(withNop(bytes, names, beforeLast = true), build.lookup)
        for ((name, descriptor) in expected) {
            val label = "scalac $version $simpleName.$name$descriptor"
            assertEquals(GeneratedBy.ENUM, original.generatedBy(name, descriptor), label)
            assertEquals(GeneratedBy.NONE, withExtra.generatedBy(name, descriptor), "$label with a nop")
            assertEquals(GeneratedBy.NONE, withExtraAtEnd.generatedBy(name, descriptor), "$label with a nop before its last instruction")
        }
    }

    private val productForwarders =
        listOf(
            "productIterator" to "()Lscala/collection/Iterator;",
            "productPrefix" to "()Ljava/lang/String;",
            "productElementName" to "(I)Ljava/lang/String;",
            "productElementNames" to "()Lscala/collection/Iterator;",
        )

    private fun companionMethods(enumName: String) =
        listOf(
            "values" to "()[L$PACKAGE/$enumName;",
            "valueOf" to "(Ljava/lang/String;)L$PACKAGE/$enumName;",
            "fromOrdinal" to "(I)L$PACKAGE/$enumName;",
            "\$new" to "(ILjava/lang/String;)L$PACKAGE/$enumName;",
            "ordinal" to "(L$PACKAGE/$enumName;)I",
            "ordinal" to "(Ljava/lang/Object;)I",
        )

    private val singletonCaseMethods =
        listOf(
            "canEqual" to "(Ljava/lang/Object;)Z",
            "productArity" to "()I",
            "productElement" to "(I)Ljava/lang/Object;",
            "productElementName" to "(I)Ljava/lang/String;",
            "fromProduct" to "(Lscala/Product;)Lscala/deriving/Mirror\$Singleton;",
            "fromProduct" to "(Lscala/Product;)Ljava/lang/Object;",
            "readResolve" to "()Ljava/lang/Object;",
            "productPrefix" to "()Ljava/lang/String;",
            "toString" to "()Ljava/lang/String;",
            "ordinal" to "()I",
            "hashCode" to "()I",
        )

    @Test
    fun `scala 3 - an enum class's Product forwarders are read exactly`() {
        for (version in listOf("3.3.4") + scala3Versions) {
            for (enumName in listOf("Suit", "Planet", "Shape", "Level", "Color")) {
                assertEnumReadExactly(version, enumName, productForwarders)
            }
        }
    }

    @Test
    fun `scala 3 - an enum companion's values, valueOf, fromOrdinal, new and ordinal are read exactly in each lowering`() {
        for (version in listOf("3.3.4") + scala3Versions) {
            // Suit has three cases and a hash switch in valueOf, Planet and Level two and a chain; Planet and Suit
            // index $values inside a handler in fromOrdinal.
            for (enumName in listOf("Suit", "Hue")) assertEnumReadExactly(version, "$enumName\$", companionMethods(enumName))
            val withoutNew = companionMethods("Planet").filter { it.first != "\$new" }
            assertEnumReadExactly(version, "Planet\$", withoutNew)
            assertEnumReadExactly(version, "Level\$", companionMethods("Level"))
            assertEnumReadExactly(version, "Color\$", companionMethods("Color"))
        }
    }

    @Test
    fun `scala 3 - the companion of an enum with a parameterised case reads its ordinal tests and new exactly`() {
        for (version in listOf("3.3.4") + scala3Versions) {
            val methods = companionMethods("Shape").filter { it.first != "values" && it.first != "valueOf" }
            assertEnumReadExactly(version, "Shape\$", methods)
            assertEnumReadExactly(version, "Shape\$Circle", listOf("ordinal" to "()I"))
            assertEnumReadExactly(version, "Shape\$Square", listOf("ordinal" to "()I"))
        }
    }

    @Test
    fun `scala 3 - an enum extending a class of the adopter's reads its plumbing exactly`() {
        for (version in listOf("3.3.4") + scala3Versions) {
            val build = if (version == "3.3.4") CompilerFixtures.scalaBaseline(version) else CompilerFixtures.scalac(version)
            assertEnumReadExactly(version, "Tone", productForwarders)
            assertEnumReadExactly(version, "Tone\$", companionMethods("Tone"))
            assertEnumReadExactly(version, build.classNames().single { it.startsWith("Tone\$\$anon") }, singletonCaseMethods)
            assertEnumReadExactly(version, "Mark", productForwarders)
            assertEnumReadExactly(version, "Mark\$", companionMethods("Mark").filter { it.first != "values" && it.first != "valueOf" })
            assertEnumReadExactly(version, build.classNames().single { it.startsWith("Mark\$\$anon") }, singletonCaseMethods)
            assertEnumReadExactly(version, "Mark\$Scored", listOf("ordinal" to "()I"))
        }
    }

    @Test
    fun `scala 3 - a case of an enum extending a class of the adopter's is held on a read release when its body is not read`() {
        val build = CompilerFixtures.scalaBaseline("3.3.4")
        val singleton = build.classNames().single { it.startsWith("Mark\$\$anon") }

        val analysis = analyzeWith(withNop(build.classBytes(singleton), setOf("canEqual")), build.lookup, tastyOf("Mark", "Scala 3.3.4"))
        val scored = analyzeWith(withNop(build.classBytes("Mark\$Scored"), setOf("ordinal")), build.lookup, tastyOf("Mark", "Scala 3.3.4"))

        assertEquals(UnreadShape.SCALA_ENUM, analysis.unreadShape("canEqual", "(Ljava/lang/Object;)Z"))
        assertEquals(UnreadShape.SCALA_ENUM, scored.unreadShape("ordinal", "()I"))
    }

    @Test
    fun `scala 3 - an enum nested in an object or with a singleton case of its own class reads its plumbing exactly`() {
        for (version in listOf("3.3.4") + scala3Versions) {
            val build = if (version == "3.3.4") CompilerFixtures.scalaBaseline(version) else CompilerFixtures.scalac(version)
            val withoutNames = { enumName: String -> companionMethods(enumName).filter { it.first != "values" && it.first != "valueOf" } }
            assertEnumReadExactly(version, "CaseHost\$Kind\$", withoutNames("CaseHost\$Kind"))
            assertEnumReadExactly(version, "CaseHost\$Kind\$Box", listOf("ordinal" to "()I"))
            assertEnumReadExactly(version, "Coin\$", withoutNames("Coin").filter { it.first != "\$new" })
            assertEnumReadExactly(version, "Coin\$Custom", listOf("ordinal" to "()I"))
            val penny = build.classNames().single { it.startsWith("Coin\$\$anon") }
            assertEnumReadExactly(version, penny, singletonCaseMethods)
        }
    }

    private fun nestedAccessor(
        enumName: String,
        case: String,
    ) = case to "()L$PACKAGE/$enumName;"

    private fun nestedCompanionMethods(
        enumName: String,
        cases: List<String>,
        parameterised: Boolean = false,
        withNew: Boolean = true,
    ): List<Pair<String, String>> =
        companionMethods(enumName)
            .filter { !parameterised || (it.first != "values" && it.first != "valueOf") }
            .filter { withNew || it.first != "\$new" } + cases.map { nestedAccessor(enumName, it) }

    private val nestedEnums =
        listOf(
            Triple("NestedSingletons\$Compass", listOf("North", "East", "South", "West"), "NestedSingletons\$\$anon\$1"),
            Triple("NestedSingletons\$Side", listOf("Left", "Right"), "NestedSingletons\$\$anon\$2"),
            Triple("NestedMixed\$Token", listOf("Space", "Tab"), "NestedMixed\$\$anon\$3"),
            Triple("NestedCompanion\$Gate", listOf("Open", "Closed"), "NestedCompanion\$\$anon\$6"),
        )

    @Test
    fun `scala 3 - the companion of an enum declared inside a class reads its plumbing and case accessors exactly`() {
        for (version in listOf("3.3.4") + scala3Versions) {
            val build = if (version == "3.3.4") CompilerFixtures.scalaBaseline(version) else CompilerFixtures.scalac(version)
            for ((enumName, cases, _) in nestedEnums) {
                val mixed = enumName == "NestedMixed\$Token"
                val methods = nestedCompanionMethods(enumName, cases, parameterised = mixed)
                assertEnumReadExactly(version, "$enumName\$", methods)
            }
            val holder = build.classNames().single { it.startsWith("EnumHolder\$\$anon") }
            assertEnumReadExactly(version, "EnumHolder\$Inner\$", nestedCompanionMethods("EnumHolder\$Inner", listOf("Up", "Down")))
            assertEnumReadExactly(version, holder, singletonCaseMethods)
        }
    }

    @Test
    fun `scala 3 - the companion of a class-nested enum whose cases extend its constructor reads without a new`() {
        for (version in listOf("3.3.4") + scala3Versions) {
            val methods = nestedCompanionMethods("NestedParam\$Rank", listOf("Low", "High"), withNew = false)
            assertEnumReadExactly(version, "NestedParam\$Rank\$", methods)
            for (singleton in listOf("NestedParam\$\$anon\$4", "NestedParam\$\$anon\$5")) {
                assertEnumReadExactly(version, singleton, singletonCaseMethods)
            }
        }
    }

    @Test
    fun `scala 3 - the singleton cases and parameterised case of a class-nested enum read exactly`() {
        for (version in listOf("3.3.4") + scala3Versions) {
            for ((_, _, singleton) in nestedEnums) assertEnumReadExactly(version, singleton, singletonCaseMethods)
            assertEnumReadExactly(version, "NestedMixed\$Token\$Word", listOf("ordinal" to "()I"))
        }
    }

    @Test
    fun `scala 3 - the accessor of a class-nested enum's parameterised case is read from the field kept for that case`() {
        val build = CompilerFixtures.scalaBaseline("3.3.4")
        val word = "Word" to "()L$PACKAGE/NestedMixed\$Token\$Word\$;"

        assertEquals(GeneratedBy.ENUM, build.marks("NestedMixed\$Token\$").of(word.first, word.second))
        val renamed =
            withBody(build.classBytes("NestedMixed\$Token\$"), word.first) { v ->
                v.visitVarInsn(Opcodes.ALOAD, 0)
                v.visitFieldInsn(Opcodes.GETFIELD, "$PACKAGE/NestedMixed\$Token\$", "Tab", "L$PACKAGE/NestedMixed\$Token;")
                v.visitInsn(Opcodes.ARETURN)
            }
        val secondField =
            ClassWriter(0)
                .also { writer ->
                    ClassReader(build.classBytes("NestedMixed\$Token\$")).accept(
                        object : ClassVisitor(Opcodes.ASM9, writer) {
                            override fun visitEnd() {
                                super.visitField(
                                    Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL,
                                    "Word\$lzy9",
                                    word.second.removePrefix("()"),
                                    null,
                                    null,
                                )
                                super.visitEnd()
                            }
                        },
                        0,
                    )
                }.toByteArray()

        assertEquals(GeneratedBy.ENUM, analyze(secondField, build.lookup).generatedBy(word.first, word.second))
        val caseObject = word.second.removePrefix("()")
        val withOther =
            ClassWriter(0)
                .also { writer ->
                    ClassReader(build.classBytes("NestedMixed\$Token\$")).accept(
                        object : ClassVisitor(Opcodes.ASM9, writer) {
                            override fun visitEnd() {
                                super.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, "other", caseObject, null, null)
                                super.visitEnd()
                            }
                        },
                        0,
                    )
                }.toByteArray()
        for (field in listOf("other", "Word\$lzy9")) {
            val reads =
                withBody(withOther, word.first) { v ->
                    v.visitVarInsn(Opcodes.ALOAD, 0)
                    v.visitFieldInsn(Opcodes.GETFIELD, "$PACKAGE/NestedMixed\$Token\$", field, caseObject)
                    v.visitInsn(Opcodes.ARETURN)
                }

            assertEquals(GeneratedBy.NONE, analyze(reads, build.lookup).generatedBy(word.first, word.second), field)
        }
        assertEquals(GeneratedBy.NONE, analyze(renamed, build.lookup).generatedBy(word.first, word.second))
    }

    @Test
    fun `scala 3 - a hand-written value or method in a class-nested enum's companion is not marked and is no case`() {
        val build = CompilerFixtures.scalaBaseline("3.3.4")
        val gate = "L$PACKAGE/NestedCompanion\$Gate;"
        val companion = build.marks("NestedCompanion\$Gate\$")

        assertEquals(GeneratedBy.NONE, companion.of("fallback", "()$gate"))
        assertEquals(GeneratedBy.NONE, companion.of("parse", "(Ljava/lang/String;)$gate"))
        assertEquals(
            GeneratedBy.ENUM,
            companion.of("valueOf", "(Ljava/lang/String;)$gate"),
            "the value is no case, so the cases are Open and Closed",
        )
        assertEquals(GeneratedBy.ENUM, companion.of("fromOrdinal", "(I)$gate"))
    }

    @Test
    fun `scala 3 - a field is a singleton case of a class-nested enum only when its accessor has the case bit`() {
        val build = CompilerFixtures.scalaBaseline("3.3.4")
        val gate = "L$PACKAGE/NestedCompanion\$Gate;"
        val bytes = build.classBytes("NestedCompanion\$Gate\$")
        val caseBit = 0x4000

        val withoutBit = withMethodAccess(bytes, "Open", "()$gate") { it and caseBit.inv() }
        val fallbackWithBit = withMethodAccess(bytes, "fallback", "()$gate") { it or caseBit }
        val noBit = analyze(withoutBit, build.lookup)
        val extraCase = analyze(fallbackWithBit, build.lookup)

        assertEquals(GeneratedBy.NONE, noBit.generatedBy("Open", "()$gate"))
        assertEquals(GeneratedBy.NONE, noBit.generatedBy("valueOf", "(Ljava/lang/String;)$gate"), "Open is no case, so valueOf misses it")
        assertEquals(GeneratedBy.NONE, extraCase.generatedBy("valueOf", "(Ljava/lang/String;)$gate"), "a third case is not in valueOf")
    }

    @Test
    fun `scala 3 - a class-nested enum's companion paired with no enum class marks nothing`() {
        val build = CompilerFixtures.scalaBaseline("3.3.4")
        val notEnum =
            withHeader(build.classBytes("NestedCompanion\$Gate"), access = { it and Opcodes.ACC_ENUM.inv() })
        val missing = { name: String -> if (name == "$PACKAGE/NestedCompanion\$Gate") null else build.lookup(name) }
        val lookups =
            mapOf(
                "not an enum class" to { name: String -> if (name == "$PACKAGE/NestedCompanion\$Gate") notEnum else build.lookup(name) },
                "no class of that name" to missing,
            )

        for ((label, lookup) in lookups) {
            val analysis = analyze(build.classBytes("NestedCompanion\$Gate\$"), lookup)
            val gate = "L$PACKAGE/NestedCompanion\$Gate;"
            for ((name, descriptor) in nestedCompanionMethods("NestedCompanion\$Gate", listOf("Open", "Closed"))) {
                assertEquals(GeneratedBy.NONE, analysis.generatedBy(name, descriptor), "$label $name$descriptor")
            }
            assertEquals(GeneratedBy.NONE, analysis.generatedBy("Open", "()$gate"), label)
        }
    }

    @Test
    fun `scala 3 - a class-nested enum's refused methods stay unread shapes on a read release, its ordinal and plain accessor do not`() {
        val build = CompilerFixtures.scalaBaseline("3.3.4")
        val gate = "L$PACKAGE/NestedCompanion\$Gate;"
        val refused =
            listOf(
                "values" to "()[$gate",
                "valueOf" to "(Ljava/lang/String;)$gate",
                "fromOrdinal" to "(I)$gate",
                "\$new" to "(ILjava/lang/String;)$gate",
                "ordinal" to "(Ljava/lang/Object;)I",
                "Open" to "()$gate",
            )
        val tasty = tastyOf("NestedCompanion", "Scala 3.3.4")

        for ((name, descriptor) in refused) {
            val analysis = analyzeWith(withNop(build.classBytes("NestedCompanion\$Gate\$"), setOf(name)), build.lookup, tasty)
            assertEquals(UnreadShape.SCALA_ENUM, analysis.unreadShape(name, descriptor), "$name$descriptor")
            assertEquals(GeneratedBy.NONE, analysis.generatedBy(name, descriptor), "$name$descriptor")
            assertNull(analysis.unreadRelease, "$name$descriptor")
        }
        for ((name, descriptor) in listOf("ordinal" to "($gate)I", "fallback" to "()$gate", "parse" to "(Ljava/lang/String;)$gate")) {
            val analysis = analyzeWith(withNop(build.classBytes("NestedCompanion\$Gate\$"), setOf(name)), build.lookup, tasty)
            assertEquals(UnreadShape.NONE, analysis.unreadShape(name, descriptor), "$name$descriptor")
        }
    }

    @Test
    fun `scala 3 - the accessor of a class-nested enum's parameterised case with an unread body is an unread shape on a read release`() {
        val build = CompilerFixtures.scalaBaseline("3.3.4")
        val word = "Word" to "()L$PACKAGE/NestedMixed\$Token\$Word\$;"

        val analysis =
            analyzeWith(
                withNop(build.classBytes("NestedMixed\$Token\$"), setOf("Word")),
                build.lookup,
                tastyOf("NestedMixed", "Scala 3.3.4"),
            )

        assertEquals(UnreadShape.SCALA_ENUM, analysis.unreadShape(word.first, word.second))
    }

    @Test
    fun `scala 3 - a class-nested enum's singleton case stays an unread shape on a read release when its readResolve is not read`() {
        val build = CompilerFixtures.scalaBaseline("3.3.4")

        val analysis =
            analyzeWith(
                withNop(build.classBytes("NestedSingletons\$\$anon\$1"), setOf("readResolve")),
                build.lookup,
                tastyOf("NestedSingletons", "Scala 3.3.4"),
            )

        assertEquals(UnreadShape.SCALA_ENUM, analysis.unreadShape("readResolve", "()Ljava/lang/Object;"))
    }

    @Test
    fun `scala 3 - a class-nested enum singleton's readResolve needs its outer field to be the companion`() {
        val build = CompilerFixtures.scalaBaseline("3.3.4")
        val bytes = build.classBytes("NestedSingletons\$\$anon\$1")
        val other = withField(bytes, "\$outer", descriptor = { "Ljava/lang/Object;" })

        assertEquals(GeneratedBy.ENUM, analyze(bytes, build.lookup).generatedBy("readResolve", "()Ljava/lang/Object;"))
        assertEquals(GeneratedBy.NONE, analyze(other, build.lookup).generatedBy("readResolve", "()Ljava/lang/Object;"))
    }

    /** [classBytes] with the access flags and descriptor of the field [name] passed through [access] and [descriptor]. */
    private fun withField(
        classBytes: ByteArray,
        name: String,
        access: (Int) -> Int = { it },
        descriptor: (String) -> String = { it },
    ): ByteArray {
        val writer = ClassWriter(0)
        val visitor =
            object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visitField(
                    flags: Int,
                    fieldName: String,
                    fieldDescriptor: String,
                    signature: String?,
                    value: Any?,
                ) = if (fieldName == name) {
                    super.visitField(access(flags), fieldName, descriptor(fieldDescriptor), signature, value)
                } else {
                    super.visitField(flags, fieldName, fieldDescriptor, signature, value)
                }
            }
        ClassReader(classBytes).accept(visitor, 0)
        return writer.toByteArray()
    }

    /** [classBytes] with the body of the method [name] replaced by what [emit] writes. */
    private fun withBody(
        classBytes: ByteArray,
        name: String,
        emit: (MethodVisitor) -> Unit,
    ): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        val visitor =
            object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visitMethod(
                    access: Int,
                    methodName: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    val delegate = super.visitMethod(access, methodName, descriptor, signature, exceptions)
                    if (methodName != name) return delegate
                    delegate.visitCode()
                    emit(delegate)
                    delegate.visitMaxs(0, 0)
                    delegate.visitEnd()
                    return null
                }
            }
        ClassReader(classBytes).accept(visitor, 0)
        return writer.toByteArray()
    }

    @Test
    fun `scala 3 - a class-nested enum's parameterised case accessor is read whatever number scalac gives its field`() {
        for (version in listOf("3.3.4") + scala3Versions) {
            for ((enumName, singleton) in listOf(
                "NestedMixed\$Token" to "Space",
                "NestedSharedName\$Token" to "Blank",
                "NestedSharedNameAgain\$Token" to "Gap",
            )) {
                val word = "Word" to "()L$PACKAGE/$enumName\$Word\$;"
                assertEnumReadExactly(
                    version,
                    "$enumName\$",
                    nestedCompanionMethods(enumName, listOf(singleton), parameterised = true) + word,
                )
            }
        }
        val build = CompilerFixtures.scalaBaseline("3.3.4")
        assertEquals(GeneratedBy.NONE, build.marks("NestedSharedName").of("Word", "()L$PACKAGE/NestedSharedName\$Word\$;"))
    }

    @Test
    fun `scala 3 - a class-nested enum's singleton case carrying an annotation is still a case`() {
        for (version in listOf("3.3.4") + scala3Versions) {
            val build = if (version == "3.3.4") CompilerFixtures.scalaBaseline(version) else CompilerFixtures.scalac(version)
            assertEnumReadExactly(
                version,
                "NestedAnnotated\$Era\$",
                nestedCompanionMethods("NestedAnnotated\$Era", listOf("Old", "Mid", "New")),
            )
            assertEnumReadExactly(version, build.classNames().single { it.startsWith("NestedAnnotated\$\$anon") }, singletonCaseMethods)
        }
    }

    @Test
    fun `scala 3 - a class-nested enum's companion reads only the instance forms of new and values`() {
        val build = CompilerFixtures.scalaBaseline("3.3.4")
        val companion = build.classBytes("EnumHolder\$Inner\$")
        val inner = "L$PACKAGE/EnumHolder\$Inner;"
        val singleton = "$PACKAGE/" + build.classNames().single { it.startsWith("EnumHolder\$\$anon") }
        val plainNew =
            withBody(companion, "\$new") { v ->
                v.visitTypeInsn(Opcodes.NEW, singleton)
                v.visitInsn(Opcodes.DUP)
                v.visitVarInsn(Opcodes.ALOAD, 2)
                v.visitVarInsn(Opcodes.ILOAD, 1)
                v.visitMethodInsn(Opcodes.INVOKESPECIAL, singleton, "<init>", "(Ljava/lang/String;I)V", false)
                v.visitInsn(Opcodes.ARETURN)
            }
        val staticValues = withField(companion, "\$values", access = { it or Opcodes.ACC_STATIC })

        assertEquals(GeneratedBy.ENUM, analyze(companion, build.lookup).generatedBy("\$new", "(ILjava/lang/String;)$inner"))
        assertEquals(GeneratedBy.NONE, analyze(plainNew, build.lookup).generatedBy("\$new", "(ILjava/lang/String;)$inner"))
        assertEquals(GeneratedBy.ENUM, analyze(companion, build.lookup).generatedBy("values", "()[$inner"))
        assertEquals(GeneratedBy.NONE, analyze(staticValues, build.lookup).generatedBy("values", "()[$inner"))
    }

    @Test
    fun `scala 3 - the accessor of a final case class the adopter nests in a class-nested enum's companion is the adopter's`() {
        val build = CompilerFixtures.scalaBaseline("3.3.4")
        val hint = "Hint" to "()L$PACKAGE/NestedCompanionHint\$Mode\$Hint\$;"

        val analysis =
            analyzeWith(build.classBytes("NestedCompanionHint\$Mode\$"), build.lookup, tastyOf("NestedCompanionHint", "Scala 3.3.4"))

        assertEquals(GeneratedBy.NONE, analysis.generatedBy(hint.first, hint.second))
        assertEquals(UnreadShape.NONE, analysis.unreadShape(hint.first, hint.second))
        assertEquals(GeneratedBy.ENUM, analysis.generatedBy("values", "()[L$PACKAGE/NestedCompanionHint\$Mode;"))
    }

    @Test
    fun `scala 3 - a class-nested enum's parameterised case accessor stays held on a read release when its case cannot be read`() {
        val build = CompilerFixtures.scalaBaseline("3.3.4")
        val word = "Word" to "()L$PACKAGE/NestedMixed\$Token\$Word\$;"
        val lookup = { name: String -> if (name == "$PACKAGE/NestedMixed\$Token\$Word") null else build.lookup(name) }

        val analysis = analyzeWith(build.classBytes("NestedMixed\$Token\$"), lookup, tastyOf("NestedMixed", "Scala 3.3.4"))

        assertEquals(GeneratedBy.NONE, analysis.generatedBy(word.first, word.second))
        assertEquals(UnreadShape.SCALA_ENUM, analysis.unreadShape(word.first, word.second))
    }

    /** [classBytes] with the access flags of the method [name][descriptor] passed through [access]. */
    private fun withMethodAccess(
        classBytes: ByteArray,
        name: String,
        descriptor: String,
        access: (Int) -> Int,
    ): ByteArray {
        val writer = ClassWriter(0)
        val visitor =
            object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visitMethod(
                    flags: Int,
                    methodName: String,
                    methodDescriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ) = super.visitMethod(
                    if (methodName == name && methodDescriptor == descriptor) access(flags) else flags,
                    methodName,
                    methodDescriptor,
                    signature,
                    exceptions,
                )
            }
        ClassReader(classBytes).accept(visitor, 0)
        return writer.toByteArray()
    }

    /** [classBytes] with the constant `ordinal()I` pushes changed from [from] to [to], both between 0 and 5. */
    private fun withOrdinalConstant(
        classBytes: ByteArray,
        from: Int,
        to: Int,
    ): ByteArray {
        val writer = ClassWriter(0)
        val visitor =
            object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor {
                    val delegate = super.visitMethod(access, name, descriptor, signature, exceptions)
                    if (name != "ordinal" || descriptor != "()I") return delegate
                    return object : MethodVisitor(Opcodes.ASM9, delegate) {
                        override fun visitInsn(opcode: Int) =
                            super.visitInsn(if (opcode == Opcodes.ICONST_0 + from) Opcodes.ICONST_0 + to else opcode)
                    }
                }
            }
        ClassReader(classBytes).accept(visitor, 0)
        return writer.toByteArray()
    }

    /** [classBytes] without the fields named in [names]. */
    private fun withoutFields(
        classBytes: ByteArray,
        names: Set<String>,
    ): ByteArray {
        val writer = ClassWriter(0)
        val visitor =
            object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visitField(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    value: Any?,
                ) = if (name in names) null else super.visitField(access, name, descriptor, signature, value)
            }
        ClassReader(classBytes).accept(visitor, 0)
        return writer.toByteArray()
    }

    /** [classBytes] with its access flags, superclass and interfaces passed through [access], [superName] and [interfaces]. */
    private fun withHeader(
        classBytes: ByteArray,
        access: (Int) -> Int = { it },
        superName: (String?) -> String? = { it },
        interfaces: (List<String>) -> List<String> = { it },
    ): ByteArray {
        val writer = ClassWriter(0)
        val visitor =
            object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visit(
                    version: Int,
                    flags: Int,
                    name: String,
                    signature: String?,
                    parent: String?,
                    implemented: Array<out String>?,
                ) = super.visit(
                    version,
                    access(flags),
                    name,
                    signature,
                    superName(parent),
                    interfaces(implemented.orEmpty().toList()).toTypedArray(),
                )
            }
        ClassReader(classBytes).accept(visitor, 0)
        return writer.toByteArray()
    }

    @Test
    fun `scala 3 - an enum class needs ACC_ENUM, ACC_ABSTRACT and scala_reflect_Enum, whatever its superclass`() {
        val build = CompilerFixtures.scalaBaseline("3.3.4")
        val suit = build.classBytes("Suit")
        val variants =
            mapOf(
                "unchanged" to suit,
                "java.lang.Enum superclass" to withHeader(suit, superName = { "java/lang/Enum" }),
                "no ACC_ENUM" to withHeader(suit, access = { it and Opcodes.ACC_ENUM.inv() }),
                "no ACC_ABSTRACT" to withHeader(suit, access = { it and Opcodes.ACC_ABSTRACT.inv() }),
                "no scala.reflect.Enum" to withHeader(suit, interfaces = { it - "scala/reflect/Enum" }),
                "a class of the adopter's as superclass" to withHeader(suit, superName = { "java/lang/Number" }),
            )
        val enumClasses = setOf("unchanged", "java.lang.Enum superclass", "a class of the adopter's as superclass")
        for ((label, enumClass) in variants) {
            val lookup = { name: String -> if (name == "$PACKAGE/Suit") enumClass else build.lookup(name) }
            val expected = if (label in enumClasses) GeneratedBy.ENUM else GeneratedBy.NONE

            val analysis = analyze(build.classBytes("Suit\$"), lookup)

            assertEquals(expected, analysis.generatedBy("values", "()[L$PACKAGE/Suit;"), label)
        }
    }

    @Test
    fun `scala 3 - a class implementing EnumValue over a scala_reflect_Enum that is not an enum class is not held on a read release`() {
        val build = CompilerFixtures.scalaBaseline("3.3.4")
        val suit = build.classBytes("Suit")
        val notEnums =
            mapOf(
                "no ACC_ENUM" to withHeader(suit, access = { it and Opcodes.ACC_ENUM.inv() }),
                "no ACC_ABSTRACT" to withHeader(suit, access = { it and Opcodes.ACC_ABSTRACT.inv() }),
                "no scala.reflect.Enum" to withHeader(suit, interfaces = { it - "scala/reflect/Enum" }),
            )
        val singleton = build.classNames().first { it.startsWith("Suit\$\$anon") }
        for ((label, notEnum) in notEnums) {
            val lookup = { name: String -> if (name == "$PACKAGE/Suit") notEnum else build.lookup(name) }

            val analysis = analyzeWith(build.classBytes(singleton), lookup, tastyOf("Suit", "Scala 3.3.4"))

            assertEquals(UnreadShape.NONE, analysis.unreadShape("canEqual", "(Ljava/lang/Object;)Z"), label)
            assertEquals(GeneratedBy.NONE, analysis.generatedBy("canEqual", "(Ljava/lang/Object;)Z"), label)
        }
    }

    @Test
    fun `scala 3 - a parameterised case's ordinal stays an unread enum shape on a read release when its companion cannot be read`() {
        val build = CompilerFixtures.scalaBaseline("3.3.4")
        val lookup = { name: String -> if (name == "$PACKAGE/Shape\$") null else build.lookup(name) }

        val analysis = analyzeWith(build.classBytes("Shape\$Circle"), lookup, tastyOf("Shape", "Scala 3.3.4"))

        assertEquals(GeneratedBy.NONE, analysis.generatedBy("ordinal", "()I"))
        assertEquals(UnreadShape.SCALA_ENUM, analysis.unreadShape("ordinal", "()I"))
    }

    @Test
    fun `scala 3 - a parameterised case whose ordinal collides with another case's leaves unmarked only what needs the ordinals`() {
        val build = CompilerFixtures.scalaBaseline("3.3.4")
        val circle = withOrdinalConstant(build.classBytes("Shape\$Circle"), 0, 1)
        val lookup = { name: String -> if (name == "$PACKAGE/Shape\$Circle") circle else build.lookup(name) }
        val shape = "L$PACKAGE/Shape;"
        val singleton = build.classNames().single { it.startsWith("Shape\$\$anon") }

        val case = analyzeWith(circle, lookup, tastyOf("Shape", "Scala 3.3.4"))
        val companion = analyze(build.classBytes("Shape\$"), lookup)
        val singletonCase = analyze(build.classBytes(singleton), lookup)

        assertEquals(GeneratedBy.ENUM, build.marks("Shape\$").of("fromOrdinal", "(I)$shape"), "the unchanged family is read")
        assertEquals(GeneratedBy.NONE, case.generatedBy("ordinal", "()I"))
        assertEquals(UnreadShape.SCALA_ENUM, case.unreadShape("ordinal", "()I"))
        assertEquals(GeneratedBy.NONE, companion.generatedBy("fromOrdinal", "(I)$shape"))
        for ((name, descriptor) in listOf(
            "\$new" to "(ILjava/lang/String;)$shape",
            "ordinal" to "($shape)I",
            "ordinal" to "(Ljava/lang/Object;)I",
        )) {
            assertEquals(GeneratedBy.ENUM, companion.generatedBy(name, descriptor), "Shape$.$name$descriptor needs no ordinal")
        }
        for ((name, descriptor) in listOf(
            "canEqual" to "(Ljava/lang/Object;)Z",
            "readResolve" to "()Ljava/lang/Object;",
            "productPrefix" to "()Ljava/lang/String;",
            "ordinal" to "()I",
        )) {
            assertEquals(GeneratedBy.ENUM, singletonCase.generatedBy(name, descriptor), "$singleton.$name reads its ordinal from a field")
        }
    }

    @Test
    fun `scala 3 - a listed case class that cannot be read or has no constant ordinal leaves the cases unread`() {
        val build = CompilerFixtures.scalaBaseline("3.3.4")
        val op = "L$PACKAGE/Op;"
        val brokenLit = withNop(build.classBytes("Op\$Lit"), setOf("ordinal"))
        // Op's parameterised case must stay last: skipping it then leaves the singletons' ordinals right, so only an unread list fails fromOrdinal.
        val lookups =
            mapOf(
                "unreadable" to { name: String -> if (name == "$PACKAGE/Op\$Lit") null else build.lookup(name) },
                "no constant ordinal" to { name: String -> if (name == "$PACKAGE/Op\$Lit") brokenLit else build.lookup(name) },
            )

        assertEquals(GeneratedBy.ENUM, build.marks("Op\$").of("fromOrdinal", "(I)$op"), "the unchanged family is read")
        for ((label, lookup) in lookups) {
            val companion = analyze(build.classBytes("Op\$"), lookup)
            assertEquals(GeneratedBy.NONE, companion.generatedBy("fromOrdinal", "(I)$op"), label)
            assertEquals(GeneratedBy.ENUM, companion.generatedBy("\$new", "(ILjava/lang/String;)$op"), label)
        }
    }

    @Test
    fun `scala 3 - a parameterised case's ordinal past the case count leaves the cases unread`() {
        val build = CompilerFixtures.scalaBaseline("3.3.4")
        val neg = withOrdinalConstant(build.classBytes("Term\$Neg"), 1, 5)
        val lookup = { name: String -> if (name == "$PACKAGE/Term\$Neg") neg else build.lookup(name) }

        val analysis = analyzeWith(neg, lookup, tastyOf("Term", "Scala 3.3.4"))

        val num = analyzeWith(build.classBytes("Term\$Num"), lookup, tastyOf("Term", "Scala 3.3.4"))

        assertEquals(GeneratedBy.ENUM, build.marks("Term\$Neg").of("ordinal", "()I"), "the unchanged case is read")
        assertEquals(GeneratedBy.ENUM, build.marks("Term\$Num").of("ordinal", "()I"), "the unchanged case is read")
        for ((label, case) in listOf("Neg" to analysis, "Num" to num)) {
            assertEquals(GeneratedBy.NONE, case.generatedBy("ordinal", "()I"), label)
            assertEquals(UnreadShape.SCALA_ENUM, case.unreadShape("ordinal", "()I"), label)
        }
    }

    @Test
    fun `scala 3 - a singleton case's plumbing stays an unread enum shape on a read release when its companion cannot be read`() {
        val build = CompilerFixtures.scalaBaseline("3.3.4")
        val singleton = build.classNames().first { it.startsWith("Suit\$\$anon") }
        val lookup = { name: String -> if (name == "$PACKAGE/Suit\$") null else build.lookup(name) }

        val analysis = analyzeWith(build.classBytes(singleton), lookup, tastyOf("Suit", "Scala 3.3.4"))

        for ((name, descriptor) in listOf("ordinal" to "()I", "readResolve" to "()Ljava/lang/Object;")) {
            assertEquals(GeneratedBy.NONE, analysis.generatedBy(name, descriptor), name)
            assertEquals(UnreadShape.SCALA_ENUM, analysis.unreadShape(name, descriptor), name)
        }
    }

    /** [classBytes] with `productPrefix()Ljava/lang/String;` rewritten to the instructions [emit] writes. */
    private fun withProductPrefix(
        classBytes: ByteArray,
        emit: (MethodVisitor) -> Unit,
    ): ByteArray {
        val writer = ClassWriter(0)
        val visitor =
            object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    val target = super.visitMethod(access, name, descriptor, signature, exceptions)
                    if (name != "productPrefix" || descriptor != "()Ljava/lang/String;") return target
                    target.visitCode()
                    emit(target)
                    target.visitMaxs(1, 1)
                    target.visitEnd()
                    return null
                }
            }
        ClassReader(classBytes).accept(visitor, 0)
        return writer.toByteArray()
    }

    private val javaSingletonCaseMethods =
        singletonCaseMethods.filter { it.first !in setOf("readResolve", "toString", "ordinal", "hashCode") }

    @Test
    fun `scala 3 - a Java-compatible enum class's Product forwarders are read exactly`() {
        for (version in listOf("3.3.4") + scala3Versions) {
            for (enumName in listOf("Dir", "Metal", "JavaEnumHost\$Gear")) {
                assertEnumReadExactly(version, enumName, productForwarders)
            }
        }
    }

    @Test
    fun `scala 3 - a Java-compatible enum's companion reads its values, valueOf, fromOrdinal, new and ordinal exactly`() {
        for (version in listOf("3.3.4") + scala3Versions) {
            assertEnumReadExactly(version, "Dir\$", companionMethods("Dir"))
            assertEnumReadExactly(version, "Metal\$", companionMethods("Metal").filter { it.first != "\$new" })
            assertEnumReadExactly(version, "JavaEnumHost\$Gear\$", companionMethods("JavaEnumHost\$Gear"))
        }
    }

    @Test
    fun `scala 3 - a Java-compatible enum's singleton cases are read exactly with productPrefix as name()`() {
        for (version in listOf("3.3.4") + scala3Versions) {
            for (singleton in listOf("Dir\$\$anon\$1", "Metal\$\$anon\$2", "Metal\$\$anon\$3", "JavaEnumHost\$Gear\$\$anon\$4")) {
                assertEnumReadExactly(version, singleton, javaSingletonCaseMethods)
            }
        }
    }

    @Test
    fun `scala 3 - a Java-compatible enum's singleton has no productPrefix rule beyond name() and stays an unread shape`() {
        val build = CompilerFixtures.scalaBaseline("3.3.4")
        val prefix = "productPrefix" to "()Ljava/lang/String;"
        val bodies =
            mapOf<String, (MethodVisitor) -> Unit>(
                "a constant" to { v ->
                    v.visitLdcInsn("North")
                    v.visitInsn(Opcodes.ARETURN)
                },
                "name() on another owner" to { v ->
                    v.visitVarInsn(Opcodes.ALOAD, 0)
                    v.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "$PACKAGE/Dir", "name", "()Ljava/lang/String;", false)
                    v.visitInsn(Opcodes.ARETURN)
                },
                "toString()" to { v ->
                    v.visitVarInsn(Opcodes.ALOAD, 0)
                    v.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "$PACKAGE/Dir\$\$anon\$1", "toString", "()Ljava/lang/String;", false)
                    v.visitInsn(Opcodes.ARETURN)
                },
            )

        for ((label, emit) in bodies) {
            val bytes = withProductPrefix(build.classBytes("Dir\$\$anon\$1"), emit)
            val analysis = analyzeWith(bytes, build.lookup, tastyOf("Dir", "Scala 3.3.4"))

            assertEquals(GeneratedBy.NONE, analysis.generatedBy(prefix.first, prefix.second), label)
            assertEquals(UnreadShape.SCALA_ENUM, analysis.unreadShape(prefix.first, prefix.second), label)
        }
    }

    @Test
    fun `scala 3 - the name() productPrefix is not enum plumbing in a singleton of an enum not extending java_lang_Enum`() {
        val build = CompilerFixtures.scalaBaseline("3.3.4")
        for (singleton in listOf("Suit\$\$anon\$1", "Planet\$\$anon\$2", "Tone\$\$anon\$1", "Mark\$\$anon\$2")) {
            val bytes =
                withProductPrefix(build.classBytes(singleton)) { v ->
                    v.visitVarInsn(Opcodes.ALOAD, 0)
                    v.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "$PACKAGE/$singleton", "name", "()Ljava/lang/String;", false)
                    v.visitInsn(Opcodes.ARETURN)
                }

            val analysis = analyze(bytes, build.lookup)

            assertEquals(GeneratedBy.NONE, analysis.generatedBy("productPrefix", "()Ljava/lang/String;"), singleton)
        }
    }

    @Test
    fun `scala 3 - a parameterised case's case-class plumbing in a shape not read stays unread on a read release`() {
        val build = CompilerFixtures.scalaBaseline("3.3.4")

        val analysis =
            analyzeWith(withNop(build.classBytes("Shape\$Circle"), setOf("hashCode")), build.lookup, tastyOf("Shape", "Scala 3.3.4"))

        assertEquals(UnreadShape.CASE_CLASS, analysis.unreadShape("hashCode", "()I"))
        assertNull(analysis.unreadRelease)
    }

    @Test
    fun `scala 3 - a nested class of an enum's companion that is not final is never read for the cases`() {
        val build = CompilerFixtures.scalaBaseline("3.3.4")
        val lookup = { name: String -> if (name == "$PACKAGE/Tint\$Swatch") null else build.lookup(name) }

        assertEquals(GeneratedBy.ENUM, analyze(build.classBytes("Tint\$"), lookup).generatedBy("values", "()[L$PACKAGE/Tint;"))
    }

    @Test
    fun `scala 3 - a family is read from the companion's inner classes when it has no field for a parameterised case`() {
        val build = CompilerFixtures.scalaBaseline("3.3.4")
        val companion = withoutFields(build.classBytes("Shape\$"), setOf("Circle", "Square"))

        val analysis = analyze(companion, build.lookup)

        assertEquals(GeneratedBy.ENUM, analysis.generatedBy("fromOrdinal", "(I)L$PACKAGE/Shape;"))
        val lookup = { name: String -> if (name == "$PACKAGE/Shape\$") companion else build.lookup(name) }
        for (case in listOf("Shape\$Circle", "Shape\$Square")) {
            assertEquals(GeneratedBy.ENUM, analyze(build.classBytes(case), lookup).generatedBy("ordinal", "()I"), case)
        }
    }

    @Test
    fun `scala 3 - a singleton case's class is read exactly in both forms`() {
        for (version in listOf("3.3.4") + scala3Versions) {
            // $$anon$1 keeps the name and ordinal in fields; Planet's cases return constants.
            assertEnumReadExactly(version, "Suit\$\$anon\$1", singletonCaseMethods)
            assertEnumReadExactly(version, "Planet\$\$anon\$2", singletonCaseMethods)
            assertEnumReadExactly(version, "Planet\$\$anon\$3", singletonCaseMethods)
            assertEnumReadExactly(version, "EnumHost\$Mode\$\$anon\$6", singletonCaseMethods)
        }
    }

    @Test
    fun `scala 3 - a hand-written method on an enum, its companion or beside scalac's plumbing is not marked`() {
        val lookup = fixtureLookup("scala3")
        val level = analyze(lookup("$PACKAGE/Level")!!, lookup)
        val levelCompanion = analyze(lookup("$PACKAGE/Level\$")!!, lookup)

        for ((name, descriptor) in listOf(
            "next" to "()L$PACKAGE/Level;",
            "ordinalPlus" to "(I)I",
            "valueOf" to "(Ljava/lang/String;I)L$PACKAGE/Level;",
        )) {
            assertEquals(GeneratedBy.NONE, level.generatedBy(name, descriptor), name)
        }
        assertEquals(GeneratedBy.NONE, levelCompanion.generatedBy("parse", "(Ljava/lang/String;)L$PACKAGE/Level;"))
        assertEquals(GeneratedBy.NONE, levelCompanion.generatedBy("values", "(I)[L$PACKAGE/Level;"))
        assertEquals(GeneratedBy.ENUM, levelCompanion.generatedBy("values", "()[L$PACKAGE/Level;"))
    }

    @Test
    fun `scala 3 - a companion whose enum class cannot be read marks no enum plumbing`() {
        val lookup = fixtureLookup("scala3")

        val companion = analyze(lookup("$PACKAGE/Suit\$")!!) { null }
        val singleton = analyze(lookup("$PACKAGE/Suit\$\$anon\$1")!!) { null }

        for ((name, descriptor) in companionMethods("Suit") + singletonCaseMethods) {
            assertEquals(GeneratedBy.NONE, companion.generatedBy(name, descriptor), "Suit$.$name$descriptor")
            assertEquals(GeneratedBy.NONE, singleton.generatedBy(name, descriptor), "Suit$\$anon$1.$name$descriptor")
        }
    }

    @Test
    fun `scala 3 - an enum read as Scala 2 marks no enum plumbing`() {
        val lookup = fixtureLookup("scala3")
        val classes =
            mapOf(
                "Suit" to productForwarders,
                "Suit\$" to companionMethods("Suit"),
                "Suit\$\$anon\$1" to singletonCaseMethods,
                "Planet\$\$anon\$2" to singletonCaseMethods,
                "Shape\$Circle" to listOf("ordinal" to "()I"),
            )

        for ((simpleName, methods) in classes) {
            val analysis = analyze(relabelled(lookup("$PACKAGE/$simpleName")!!, asScala3 = false), lookup)
            for ((name, descriptor) in methods) {
                assertEquals(GeneratedBy.NONE, analysis.generatedBy(name, descriptor), "$simpleName.$name$descriptor read as Scala 2")
            }
        }
    }

    /**
     * The enum shapes a companion's other members, a parameterised case, an all-parameterised
     * enum and dense case names give, and Scala 3.3.3's own wording, over the 3.3.4 baseline and
     * every Scala 3 build in the matrix.
     */
    @ParameterizedTest(name = "scalac {0}")
    @MethodSource("scala3Builds")
    fun `scala 3 - enum plumbing beside other companion members, switching and throwing fromOrdinal and a dense valueOf are ENUM`(
        version: String,
    ) {
        val build = if (version == "baseline") CompilerFixtures.scalaBaseline("3.3.4") else CompilerFixtures.scalac(version)
        val tint = build.marks("Tint\$")
        val op = build.marks("Op\$")
        val term = build.marks("Term\$")
        val axis = build.marks("Axis3\$")
        val enumOf = { name: String -> "L$PACKAGE/$name;" }

        assertEquals(GeneratedBy.ENUM, tint.of("values", "()[${enumOf("Tint")}"), "a given and nested members are not cases")
        assertEquals(GeneratedBy.ENUM, tint.of("valueOf", "(Ljava/lang/String;)${enumOf("Tint")}"))
        assertEquals(GeneratedBy.ENUM, tint.of("fromOrdinal", "(I)${enumOf("Tint")}"))
        assertEquals(GeneratedBy.ENUM, tint.of("\$new", "(ILjava/lang/String;)${enumOf("Tint")}"))
        assertEquals(GeneratedBy.ENUM, op.of("fromOrdinal", "(I)${enumOf("Op")}"), "three singletons beside a parameterised case")
        assertEquals(GeneratedBy.ENUM, term.of("fromOrdinal", "(I)${enumOf("Term")}"), "no singleton case: only the throw")
        assertEquals(GeneratedBy.ENUM, axis.of("valueOf", "(Ljava/lang/String;)${enumOf("Axis3")}"), "dense hashes: a tableswitch")
        assertEquals(
            GeneratedBy.ENUM,
            build.marks("Gapped\$").of("fromOrdinal", "(I)${enumOf("Gapped")}"),
            "a switch whose gap goes to the throw",
        )
        assertEquals(
            GeneratedBy.ENUM,
            build.marks("Sparse\$").of("valueOf", "(Ljava/lang/String;)${enumOf("Sparse")}"),
            "a hash tableswitch with a gap",
        )
    }

    @Test
    fun `scala 3 - a fromOrdinal switch whose gap jumps to a case instead of the throw is not marked`() {
        val build = CompilerFixtures.scalaBaseline("3.3.4")
        val writer = ClassWriter(0)
        val visitor =
            object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor {
                    val delegate = super.visitMethod(access, name, descriptor, signature, exceptions)
                    if (name != "fromOrdinal") return delegate
                    return object : MethodVisitor(Opcodes.ASM9, delegate) {
                        override fun visitTableSwitchInsn(
                            min: Int,
                            max: Int,
                            dflt: Label,
                            vararg labels: Label,
                        ) {
                            // Every gap, which goes to the throw, goes to the first case instead.
                            super.visitTableSwitchInsn(
                                min,
                                max,
                                dflt,
                                *labels
                                    .map {
                                        if (it ==
                                            dflt
                                        ) {
                                            labels.first()
                                        } else {
                                            it
                                        }
                                    }.toTypedArray(),
                            )
                        }
                    }
                }
            }
        ClassReader(build.classBytes("Gapped\$")).accept(visitor, 0)
        val gapped = "L$PACKAGE/Gapped;"

        assertEquals(GeneratedBy.ENUM, build.marks("Gapped\$").of("fromOrdinal", "(I)$gapped"), "the unchanged body is read")
        assertEquals(GeneratedBy.NONE, analyze(writer.toByteArray(), build.lookup).generatedBy("fromOrdinal", "(I)$gapped"))
    }

    private fun tastyHeader(tooling: String): ByteArray = BrokenScalaFixtures.tastyHeader(tooling)

    /** Serves the `.tasty` of [className] naming [tooling], and nothing else. */
    private fun tastyOf(
        className: String,
        tooling: String?,
    ): (String) -> ByteArray? = { path -> if (tooling != null && path == "$PACKAGE/$className.tasty") tastyHeader(tooling) else null }

    private fun analyzeWith(
        classBytes: ByteArray,
        lookup: (String) -> ByteArray?,
        resources: (String) -> ByteArray?,
    ): BranchSiteAnalyzer.Analysis =
        BranchSiteAnalyzer.analyze(classBytes, lookup, listOf("com.example.scalatarget"), resourceLookup = resources) { name, _ ->
            name != "<clinit>"
        }

    private class Plumbing(
        val simpleName: String,
        val method: String,
        val descriptor: String,
        val tastyOwner: String,
        val family: UnreadShape,
    )

    private val scala3Plumbing =
        listOf(
            Plumbing("Cc", "hashCode", "()I", "Cc", UnreadShape.CASE_CLASS),
            Plumbing("Cc", "equals", "(Ljava/lang/Object;)Z", "Cc", UnreadShape.CASE_CLASS),
            Plumbing("Cc", "_1", "()I", "Cc", UnreadShape.CASE_CLASS),
            Plumbing("Cc\$", "apply", "(II)L$PACKAGE/Cc;", "Cc", UnreadShape.CASE_CLASS),
            Plumbing("Cc\$", "unapply", "(L$PACKAGE/Cc;)L$PACKAGE/Cc;", "Cc", UnreadShape.CASE_CLASS),
            Plumbing("Cc\$", "fromProduct", "(Lscala/Product;)L$PACKAGE/Cc;", "Cc", UnreadShape.CASE_CLASS),
            Plumbing("Cc", "apply", "(II)L$PACKAGE/Cc;", "Cc", UnreadShape.STATIC_FORWARDER),
            Plumbing("Cc\$", "writeReplace", "()Ljava/lang/Object;", "Cc", UnreadShape.SCALA_OBJECT),
            Plumbing("Level\$", "values", "()[L$PACKAGE/Level;", "Level", UnreadShape.SCALA_ENUM),
            Plumbing("Level", "productPrefix", "()Ljava/lang/String;", "Level", UnreadShape.SCALA_ENUM),
        )

    @Test
    fun `scala 3 - a plumbing body that matches no read shape is hand-written on a read release`() {
        val build = CompilerFixtures.scalaBaseline("3.3.4")
        for (case in scala3Plumbing) {
            val broken = withNop(build.classBytes(case.simpleName), setOf(case.method))
            val label = "${case.simpleName}.${case.method}${case.descriptor}"

            val analysis = analyzeWith(broken, build.lookup, tastyOf(case.tastyOwner, "Scala 3.3.4"))
            // An enum's companion can hold no hand-written plumbing, so it stays unread there.
            val expected = if (case.simpleName == "Level\$") case.family else UnreadShape.NONE

            assertEquals(expected, analysis.unreadShape(case.method, case.descriptor), label)
            assertEquals(GeneratedBy.NONE, analysis.generatedBy(case.method, case.descriptor), label)
            assertNull(analysis.unreadRelease, label)
        }
    }

    @Test
    fun `scala 3 - in an enum's companion or case class an unread body stays an unread shape on a read release`() {
        val build = CompilerFixtures.scalaBaseline("3.3.4")
        val read = tastyOf("Level", "Scala 3.3.4")
        val companion = analyzeWith(withNop(build.classBytes("Level\$"), setOf("valueOf", "fromOrdinal")), build.lookup, read)
        val singleton = build.classNames().first { it.startsWith("Suit\$\$anon") }
        val case = analyzeWith(withNop(build.classBytes(singleton), setOf("toString")), build.lookup, tastyOf("Suit", "Scala 3.3.4"))

        assertEquals(UnreadShape.SCALA_ENUM, companion.unreadShape("valueOf", "(Ljava/lang/String;)L$PACKAGE/Level;"))
        assertEquals(UnreadShape.SCALA_ENUM, companion.unreadShape("fromOrdinal", "(I)L$PACKAGE/Level;"))
        assertEquals(UnreadShape.SCALA_ENUM, case.unreadShape("toString", "()Ljava/lang/String;"))
        assertNull(companion.unreadRelease, "a structure the agent knows it cannot read, not an unread release")
    }

    @Test
    fun `scala 3 - an enum companion's ordinal(E) and writeReplace can be hand-written, so on a read release they are the adopter's`() {
        val build = CompilerFixtures.scalaBaseline("3.3.4")
        val ordinal = "ordinal" to "(L$PACKAGE/Level;)I"
        val writeReplace = "writeReplace" to "()Ljava/lang/Object;"
        val broken = withNop(build.classBytes("Level\$"), setOf(ordinal.first, writeReplace.first))
        val analysis = analyzeWith(broken, build.lookup, tastyOf("Level", "Scala 3.3.4"))

        assertEquals(UnreadShape.NONE, analysis.unreadShape(ordinal.first, ordinal.second))
        assertEquals(UnreadShape.NONE, analysis.unreadShape(writeReplace.first, writeReplace.second))
        assertEquals(
            UnreadShape.SCALA_ENUM,
            analysis.unreadShape("ordinal", "(Ljava/lang/Object;)I"),
            "the bridge, which a hand-written ordinal(Any) would clash with",
        )
    }

    @Test
    fun `scala 2_12 - a readResolve beside a hand-written writeReplace is still scalac's`() {
        val build = CompilerFixtures.scalac("2.12.20")
        val withHandWrittenWriteReplace =
            withMethod(build.classBytes("Cc\$"), "writeReplace", "()Ljava/lang/Object;", Opcodes.ACC_PRIVATE)

        assertEquals(
            GeneratedBy.SCALA_OBJECT,
            analyzeWith(withHandWrittenWriteReplace, build.lookup) { null }.generatedBy("readResolve", "()Ljava/lang/Object;"),
        )
    }

    @Test
    fun `scala 2 - a readResolve beside a writeReplace, or in an object that is not serializable, is the adopter's`() {
        val build = CompilerFixtures.scalaBaseline("2.13.15")
        val readResolve = "readResolve" to "()Ljava/lang/Object;"
        val besideWriteReplace = withMethod(build.classBytes("Cc\$"), readResolve.first, readResolve.second, Opcodes.ACC_PRIVATE)
        val plainObject =
            withMethod(build.classBytes("Driver\$"), readResolve.first, readResolve.second, Opcodes.ACC_PRIVATE)

        assertEquals(
            UnreadShape.NONE,
            analyzeWith(besideWriteReplace, build.lookup) { null }.unreadShape(readResolve.first, readResolve.second),
        )
        assertEquals(UnreadShape.NONE, analyzeWith(plainObject, build.lookup) { null }.unreadShape(readResolve.first, readResolve.second))
    }

    @Test
    fun `scala 2_12 - a serializable object's readResolve with an unread body is an unread shape`() {
        val build = CompilerFixtures.scalac("2.12.20")
        val broken = withNop(build.classBytes("Cc\$"), setOf("readResolve"))

        assertEquals(
            UnreadShape.SCALA_OBJECT,
            analyzeWith(broken, build.lookup) { null }.unreadShape("readResolve", "()Ljava/lang/Object;"),
        )
    }

    @Test
    fun `scala 3 - a plumbing body that matches no read shape is an unread shape on a release the agent has not read`() {
        val build = CompilerFixtures.scalaBaseline("3.3.4")
        for (case in scala3Plumbing) {
            val broken = withNop(build.classBytes(case.simpleName), setOf(case.method))
            val label = "${case.simpleName}.${case.method}${case.descriptor}"

            val analysis = analyzeWith(broken, build.lookup, tastyOf(case.tastyOwner, "Scala 3.11.0"))

            assertEquals(case.family, analysis.unreadShape(case.method, case.descriptor), label)
            assertEquals(GeneratedBy.NONE, analysis.generatedBy(case.method, case.descriptor), label)
            assertEquals("3.11.0", analysis.unreadRelease, label)
        }
    }

    @Test
    fun `scala 3 - a plumbing body that matches no read shape is an unread shape when no tasty file is readable`() {
        val build = CompilerFixtures.scalaBaseline("3.3.4")
        for (case in scala3Plumbing) {
            val broken = withNop(build.classBytes(case.simpleName), setOf(case.method))
            val label = "${case.simpleName}.${case.method}${case.descriptor}"

            val analysis = analyzeWith(broken, build.lookup) { null }

            assertEquals(case.family, analysis.unreadShape(case.method, case.descriptor), label)
            assertNull(analysis.unreadRelease, "$label is version-blind")
        }
    }

    @Test
    fun `a tasty file that is not a tasty header leaves a Scala 3 class version-blind`() {
        val build = CompilerFixtures.scalaBaseline("3.3.4")
        val broken = withNop(build.classBytes("Cc"), setOf("hashCode"))

        val analysis = analyzeWith(broken, build.lookup) { byteArrayOf(1, 2, 3) }

        assertEquals(UnreadShape.CASE_CLASS, analysis.unreadShape("hashCode", "()I"))
        assertNull(analysis.unreadRelease)
    }

    @Test
    fun `scala 3 - a method outside every outline stays the adopter's in all three cases`() {
        val build = CompilerFixtures.scalaBaseline("3.3.4")
        val written = "(Ljava/lang/String;)L$PACKAGE/Written;"
        val cases =
            listOf(
                Triple("Cc", "a", "()I"),
                Triple("Written\$", "apply", written),
                Triple("Level", "next", "()L$PACKAGE/Level;"),
                Triple("Level\$", "parse", "(Ljava/lang/String;)L$PACKAGE/Level;"),
            )
        for ((simpleName, name, descriptor) in cases) {
            val broken = withNop(build.classBytes(simpleName), setOf(name, "hashCode"))
            val owner = simpleName.removeSuffix("$")
            for (tooling in listOf("Scala 3.3.4", "Scala 3.11.0", null)) {
                val analysis = analyzeWith(broken, build.lookup, tastyOf(owner, tooling))

                assertEquals(UnreadShape.NONE, analysis.unreadShape(name, descriptor), "$simpleName.$name with $tooling")
                assertEquals(GeneratedBy.NONE, analysis.generatedBy(name, descriptor), "$simpleName.$name with $tooling")
            }
        }
    }

    @Test
    fun `a Scala 3 class asks for its release only when an outline method is unmarked`() {
        val build = CompilerFixtures.scalaBaseline("3.3.4")
        val asked = mutableListOf<String>()
        val resources = { path: String -> null.also { asked += path } }

        analyzeWith(build.classBytes("Cc"), build.lookup, resources)
        analyzeWith(build.classBytes("Cc\$"), build.lookup, resources)
        assertEquals(emptyList(), asked, "every outline method of the real Cc and Cc\$ is marked")

        analyzeWith(withNop(build.classBytes("Cc"), setOf("hashCode")), build.lookup, resources)
        assertEquals(listOf("$PACKAGE/Cc.tasty"), asked)
    }

    @Test
    fun `a nested Scala 3 class takes its enclosing top-level class's release`() {
        val build = CompilerFixtures.scalaBaseline("3.3.4")
        val inner = "Shape\$Circle"
        val broken = withNop(build.classBytes(inner), setOf("hashCode"))
        val asked = mutableListOf<String>()
        val resources = { path: String ->
            (
                if (path ==
                    "$PACKAGE/Shape.tasty"
                ) {
                    tastyHeader("Scala 3.11.0")
                } else {
                    null
                }
            ).also { asked += path }
        }

        val analysis = analyzeWith(broken, build.lookup, resources)

        assertEquals(UnreadShape.CASE_CLASS, analysis.unreadShape("hashCode", "()I"))
        assertEquals("3.11.0", analysis.unreadRelease)
        assertEquals(listOf("$PACKAGE/Shape\$Circle.tasty", "$PACKAGE/Shape.tasty"), asked)
    }

    @Test
    fun `the cache reads a top-level class's tasty once for the classes under it`() {
        val build = CompilerFixtures.scalaBaseline("3.3.4")
        val cache = BranchSiteAnalyzer.CrossClassTableCache(16)
        val asked = mutableListOf<String>()
        val resources = { path: String -> (if (path == "$PACKAGE/Cc.tasty") tastyHeader("Scala 3.11.0") else null).also { asked += path } }

        for (simpleName in listOf("Cc", "Cc\$", "Cc", "Cc\$")) {
            val analysis =
                BranchSiteAnalyzer.analyze(
                    withNop(build.classBytes(simpleName), setOf("hashCode", "apply")),
                    build.lookup,
                    listOf("com.example.scalatarget"),
                    tableCache = cache,
                    resourceLookup = resources,
                ) { name, _ -> name != "<clinit>" }
            assertEquals("3.11.0", analysis.unreadRelease, simpleName)
        }

        assertEquals(1, asked.count { it == "$PACKAGE/Cc.tasty" })
        assertEquals(1, asked.count { it == "$PACKAGE/Cc\$.tasty" })
    }

    @Test
    fun `scala 2 - a broken plumbing body is an unread shape and no tasty is asked for`() {
        val build = CompilerFixtures.scalaBaseline("2.13.16")
        val cases =
            listOf(
                Plumbing("Cc", "hashCode", "()I", "Cc", UnreadShape.CASE_CLASS),
                Plumbing("Cc", "equals", "(Ljava/lang/Object;)Z", "Cc", UnreadShape.CASE_CLASS),
                Plumbing("Cc\$", "apply", "(II)L$PACKAGE/Cc;", "Cc", UnreadShape.CASE_CLASS),
                Plumbing("Cc", "apply", "(II)L$PACKAGE/Cc;", "Cc", UnreadShape.STATIC_FORWARDER),
                Plumbing("Cc\$", "writeReplace", "()Ljava/lang/Object;", "Cc", UnreadShape.SCALA_OBJECT),
            )
        for (case in cases) {
            val broken = withNop(build.classBytes(case.simpleName), setOf(case.method))
            val label = "${case.simpleName}.${case.method}${case.descriptor}"

            val analysis = analyzeWith(broken, build.lookup) { error("a Scala 2 class has no tasty file to ask for") }

            assertEquals(case.family, analysis.unreadShape(case.method, case.descriptor), label)
            assertEquals(GeneratedBy.NONE, analysis.generatedBy(case.method, case.descriptor), label)
            assertNull(analysis.unreadRelease, label)
        }
        val accessor = analyzeWith(withNop(build.classBytes("Cc"), setOf("a", "hashCode")), build.lookup) { null }
        assertEquals(UnreadShape.NONE, accessor.unreadShape("a", "()I"), "a field accessor is the adopter's")
    }

    @Test
    fun `a method is never both generated and an unread shape across the baseline builds`() {
        for (version in listOf("2.13.16", "3.3.4")) {
            val build = CompilerFixtures.scalaBaseline(version)
            for (simpleName in build.classNames()) {
                val bytes = withNop(build.classBytes(simpleName), setOf("hashCode", "apply", "equals", "toString"))
                val analysis = analyzeWith(bytes, build.lookup) { null }
                val methods = declaredMethods(bytes)
                for ((name, descriptor) in methods) {
                    assertTrue(
                        analysis.generatedBy(name, descriptor) == GeneratedBy.NONE ||
                            analysis.unreadShape(name, descriptor) == UnreadShape.NONE,
                        "$version $simpleName.$name$descriptor",
                    )
                }
            }
        }
    }

    /** The name and descriptor of each method [classBytes] declares. */
    private fun declaredMethods(classBytes: ByteArray): List<Pair<String, String>> {
        val methods = mutableListOf<Pair<String, String>>()
        ClassReader(classBytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? = null.also { methods += name to descriptor }
            },
            ClassReader.SKIP_CODE,
        )
        return methods
    }
}
