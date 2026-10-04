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

            val analysis = analyzeWith(broken, build.lookup, tastyOf(case.tastyOwner, "Scala 3.10.0"))

            assertEquals(case.family, analysis.unreadShape(case.method, case.descriptor), label)
            assertEquals(GeneratedBy.NONE, analysis.generatedBy(case.method, case.descriptor), label)
            assertEquals("3.10.0", analysis.unreadRelease, label)
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
            for (tooling in listOf("Scala 3.3.4", "Scala 3.10.0", null)) {
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
                    tastyHeader("Scala 3.10.0")
                } else {
                    null
                }
            ).also { asked += path }
        }

        val analysis = analyzeWith(broken, build.lookup, resources)

        assertEquals(UnreadShape.CASE_CLASS, analysis.unreadShape("hashCode", "()I"))
        assertEquals("3.10.0", analysis.unreadRelease)
        assertEquals(listOf("$PACKAGE/Shape\$Circle.tasty", "$PACKAGE/Shape.tasty"), asked)
    }

    @Test
    fun `the cache reads a top-level class's tasty once for the classes under it`() {
        val build = CompilerFixtures.scalaBaseline("3.3.4")
        val cache = BranchSiteAnalyzer.CrossClassTableCache(16)
        val asked = mutableListOf<String>()
        val resources = { path: String -> (if (path == "$PACKAGE/Cc.tasty") tastyHeader("Scala 3.10.0") else null).also { asked += path } }

        for (simpleName in listOf("Cc", "Cc\$", "Cc", "Cc\$")) {
            val analysis =
                BranchSiteAnalyzer.analyze(
                    withNop(build.classBytes(simpleName), setOf("hashCode", "apply")),
                    build.lookup,
                    listOf("com.example.scalatarget"),
                    tableCache = cache,
                    resourceLookup = resources,
                ) { name, _ -> name != "<clinit>" }
            assertEquals("3.10.0", analysis.unreadRelease, simpleName)
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
