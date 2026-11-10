package dev.otherlode.instrumentation.branch

import dev.otherlode.export.CallEdge
import dev.otherlode.export.GeneratedBy
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Shapes built with ASM so each condition of the delegation-forwarder and `@JvmStatic` proxy rules is
 * pinned alone: one instruction off in a body, a field that is not the delegate, a call to another
 * method, descriptor or owner, a method that is not public or carries a line number, and a class
 * without Kotlin metadata.
 */
class DelegationForgedShapesTest {
    private companion object {
        const val CLASS = "com/example/target/ForgedDelegating"
        const val DELEGATE = "com/example/target/Delegate"
        const val COMPANION = "$CLASS\$Companion"
        const val DESCRIPTOR = "(Ljava/lang/String;)Ljava/lang/String;"
        const val INTRINSICS_OWNER = "kotlin/jvm/internal/Intrinsics"
        const val CHECK = "(Ljava/lang/Object;Ljava/lang/String;)V"
    }

    private class Field(
        val name: String,
        val descriptor: String,
        val access: Int,
    )

    private val delegateField = Field("\$\$delegate_0", "L$DELEGATE;", Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL)

    private class Shape(
        val metadata: Boolean = true,
        val access: Int = Opcodes.ACC_PUBLIC,
        val lineNumber: Boolean = false,
        val fields: List<Field>,
        val interfaces: List<String> = listOf(DELEGATE),
        val constructorLine: Boolean = true,
        val body: (MethodVisitor) -> Unit,
    )

    private fun build(shape: Shape): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER, CLASS, null, "java/lang/Object", shape.interfaces.toTypedArray())
        if (shape.metadata) writer.visitAnnotation("Lkotlin/Metadata;", true).visitEnd()
        shape.fields.forEach { writer.visitField(it.access, it.name, it.descriptor, null, null).visitEnd() }
        writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            if (shape.constructorLine) {
                val start = Label()
                visitLabel(start)
                visitLineNumber(1, start)
            }
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitMethod(shape.access, "describe", DESCRIPTOR, null, null).apply {
            visitCode()
            if (shape.lineNumber) {
                val start = Label()
                visitLabel(start)
                visitLineNumber(3, start)
            }
            shape.body(this)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun mark(shape: Shape): GeneratedBy =
        BranchSiteAnalyzer.analyze(build(shape)) { _, _ -> true }.generatedBy("describe", DESCRIPTOR)

    private fun MethodVisitor.nullCheck(
        slot: Int = 1,
        owner: String = INTRINSICS_OWNER,
        name: String = "checkNotNullParameter",
    ) {
        visitVarInsn(Opcodes.ALOAD, slot)
        visitLdcInsn("text")
        visitMethodInsn(Opcodes.INVOKESTATIC, owner, name, CHECK, false)
    }

    private fun MethodVisitor.forward(
        field: Field = delegateField,
        fieldOwner: String = CLASS,
        callOpcode: Int = Opcodes.INVOKEINTERFACE,
        callOwner: String = DELEGATE,
        callName: String = "describe",
        callDescriptor: String = DESCRIPTOR,
        argumentSlot: Int = 1,
        returnOpcode: Int = Opcodes.ARETURN,
        extraBeforeReturn: Boolean = false,
    ) {
        visitVarInsn(Opcodes.ALOAD, 0)
        visitFieldInsn(Opcodes.GETFIELD, fieldOwner, field.name, field.descriptor)
        visitVarInsn(Opcodes.ALOAD, argumentSlot)
        visitMethodInsn(callOpcode, callOwner, callName, callDescriptor, callOpcode == Opcodes.INVOKEINTERFACE)
        if (extraBeforeReturn) visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String")
        visitInsn(returnOpcode)
    }

    private fun delegation(
        fields: List<Field> = listOf(delegateField),
        metadata: Boolean = true,
        access: Int = Opcodes.ACC_PUBLIC,
        lineNumber: Boolean = false,
        interfaces: List<String> = listOf(DELEGATE),
        constructorLine: Boolean = true,
        body: MethodVisitor.() -> Unit = {
            nullCheck()
            forward()
        },
    ) = Shape(metadata, access, lineNumber, fields, interfaces, constructorLine, body)

    @Test
    fun `the shape kotlinc writes is marked CLASS_DELEGATION, so the shapes below differ from it by one condition`() {
        assertEquals(GeneratedBy.CLASS_DELEGATION, mark(delegation()))
        assertEquals(GeneratedBy.CLASS_DELEGATION, mark(delegation { forward() }))
    }

    @Test
    fun `a forwarder to a class-typed delegate by invokevirtual is marked`() {
        val field = Field("\$\$delegate_1", "L$DELEGATE;", Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL)
        assertEquals(
            GeneratedBy.CLASS_DELEGATION,
            mark(delegation(fields = listOf(field)) { forward(field = field, callOpcode = Opcodes.INVOKEVIRTUAL) }),
        )
    }

    @Test
    fun `a forwarder that reads the private final field of a constructor property is marked`() {
        val field = Field("inner", "L$DELEGATE;", Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL)
        assertEquals(GeneratedBy.CLASS_DELEGATION, mark(delegation(fields = listOf(field)) { forward(field = field) }))
    }

    private val reusedField = Field("dao", "L$DELEGATE;", Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL)

    @Test
    fun `a hand-written method in a class that implements nothing and has no line numbers is not marked, as a Repo over a Dao`() {
        assertEquals(
            GeneratedBy.NONE,
            mark(delegation(fields = listOf(reusedField), interfaces = emptyList(), constructorLine = false) { forward(field = reusedField) }),
        )
        assertEquals(
            GeneratedBy.NONE,
            mark(delegation(fields = listOf(reusedField), interfaces = emptyList()) { forward(field = reusedField) }),
        )
    }

    @Test
    fun `a hand-written decorator that implements the interface and has no line numbers is not marked`() {
        assertEquals(
            GeneratedBy.NONE,
            mark(delegation(fields = listOf(reusedField), constructorLine = false) { nullCheck(); forward(field = reusedField) }),
        )
    }

    @Test
    fun `a reused-field forwarder is marked when the class has line numbers and implements the field's type directly`() {
        assertEquals(
            GeneratedBy.CLASS_DELEGATION,
            mark(delegation(fields = listOf(reusedField), interfaces = listOf("com/example/target/Extra", DELEGATE)) { forward(field = reusedField) }),
        )
    }

    @Test
    fun `a reused-field forwarder whose field type is not a direct interface of the class is not marked, as for a delegate typed as a sub-interface`() {
        assertEquals(
            GeneratedBy.NONE,
            mark(delegation(fields = listOf(reusedField), interfaces = listOf("com/example/target/Base")) { forward(field = reusedField) }),
        )
    }

    @Test
    fun `a delegate-named forwarder in a class with no line numbers is still marked`() {
        assertEquals(GeneratedBy.CLASS_DELEGATION, mark(delegation(constructorLine = false)))
    }

    @Test
    fun `a delegate-named forwarder in a class that implements no interface is not considered`() {
        assertEquals(GeneratedBy.NONE, mark(delegation(interfaces = emptyList())))
    }

    @Test
    fun `a forwarder that reads a field that is not private, not final or static is not marked`() {
        listOf(Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, Opcodes.ACC_PRIVATE, Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL or Opcodes.ACC_STATIC)
            .forEach { access ->
                val field = Field("inner", "L$DELEGATE;", access)
                assertEquals(GeneratedBy.NONE, mark(delegation(fields = listOf(field)) { forward(field = field) }), "access $access")
                val named = Field("\$\$delegate_0", "L$DELEGATE;", access)
                assertEquals(GeneratedBy.NONE, mark(delegation(fields = listOf(named)) { forward(field = named) }), "named, access $access")
            }
    }

    @Test
    fun `a forwarder whose field is not declared by the class is not marked`() {
        assertEquals(GeneratedBy.NONE, mark(delegation(fields = emptyList())))
        assertEquals(GeneratedBy.NONE, mark(delegation { nullCheck(); forward(fieldOwner = "com/example/target/Other") }))
    }

    @Test
    fun `a forwarder whose field is declared with another type than the one it calls is not marked`() {
        val field = Field("\$\$delegate_0", "Lcom/example/target/Unrelated;", Opcodes.ACC_PRIVATE or Opcodes.ACC_FINAL)
        assertEquals(GeneratedBy.NONE, mark(delegation(fields = listOf(field)) { nullCheck(); forward(field = field) }))
    }

    @Test
    fun `a forwarder that calls another name, descriptor or owner is not marked`() {
        assertEquals(GeneratedBy.NONE, mark(delegation { nullCheck(); forward(callName = "other") }))
        assertEquals(GeneratedBy.NONE, mark(delegation { nullCheck(); forward(callDescriptor = "(Ljava/lang/String;)Ljava/lang/Object;") }))
        assertEquals(GeneratedBy.NONE, mark(delegation { nullCheck(); forward(callOwner = "com/example/target/Another") }))
    }

    @Test
    fun `a forwarder that calls with another opcode is not marked`() {
        assertEquals(GeneratedBy.NONE, mark(delegation { nullCheck(); forward(callOpcode = Opcodes.INVOKESPECIAL) }))
    }

    @Test
    fun `a forwarder with one instruction more is not marked`() {
        assertEquals(GeneratedBy.NONE, mark(delegation { nullCheck(); forward(extraBeforeReturn = true) }))
        assertEquals(
            GeneratedBy.NONE,
            mark(
                delegation {
                    nullCheck()
                    visitInsn(Opcodes.NOP)
                    forward()
                },
            ),
        )
    }

    @Test
    fun `a forwarder that loads another slot or returns another type is not marked`() {
        assertEquals(GeneratedBy.NONE, mark(delegation { forward(argumentSlot = 2) }))
        assertEquals(GeneratedBy.NONE, mark(delegation { forward(returnOpcode = Opcodes.IRETURN) }))
    }

    @Test
    fun `a null check that is not kotlinc's, is out of place or is on a slot that is not a parameter is not marked`() {
        assertEquals(GeneratedBy.NONE, mark(delegation { nullCheck(owner = "com/example/target/Checks"); forward() }))
        assertEquals(GeneratedBy.NONE, mark(delegation { nullCheck(name = "other"); forward() }))
        assertEquals(GeneratedBy.NONE, mark(delegation { nullCheck(slot = 0); forward() }))
        assertEquals(GeneratedBy.NONE, mark(delegation { nullCheck(slot = 2); forward() }))
        assertEquals(GeneratedBy.NONE, mark(delegation { forward(); nullCheck() }))
    }

    @Test
    fun `a null check twice on one parameter is not marked`() {
        assertEquals(GeneratedBy.NONE, mark(delegation { nullCheck(); nullCheck(); forward() }))
    }

    @Test
    fun `a method with a line number is the adopter's and is not marked`() {
        assertEquals(GeneratedBy.NONE, mark(delegation(lineNumber = true)))
    }

    @Test
    fun `a method that is not public, or is static, synthetic or a bridge, is not marked`() {
        listOf(0, Opcodes.ACC_PROTECTED, Opcodes.ACC_PRIVATE, Opcodes.ACC_PUBLIC or Opcodes.ACC_SYNTHETIC, Opcodes.ACC_PUBLIC or Opcodes.ACC_BRIDGE)
            .forEach { assertEquals(GeneratedBy.NONE, mark(delegation(access = it)), "access $it") }
    }

    @Test
    fun `a class without Kotlin metadata is not marked, with a Java delegate field of the same name and body`() {
        assertEquals(GeneratedBy.NONE, mark(delegation(metadata = false)))
    }

    private val companionField = Field("Companion", "L$COMPANION;", Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_STATIC)

    private fun proxy(
        fields: List<Field> = listOf(companionField),
        metadata: Boolean = true,
        access: Int = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
        lineNumber: Boolean = false,
        body: MethodVisitor.() -> Unit = { proxyBody() },
    ) = Shape(metadata, access, lineNumber, fields, body = body)

    private fun MethodVisitor.proxyBody(
        field: Field = companionField,
        fieldOpcode: Int = Opcodes.GETSTATIC,
        callOpcode: Int = Opcodes.INVOKEVIRTUAL,
        callOwner: String = COMPANION,
        callName: String = "describe",
        callDescriptor: String = DESCRIPTOR,
        argumentSlot: Int = 0,
        extra: Boolean = false,
        lineAt: String? = null,
    ) {
        fun line() {
            val label = Label()
            visitLabel(label)
            visitLineNumber(7, label)
        }
        if (lineAt == "first") line()
        visitFieldInsn(fieldOpcode, CLASS, field.name, field.descriptor)
        visitVarInsn(Opcodes.ALOAD, argumentSlot)
        if (lineAt == "call") line()
        visitMethodInsn(callOpcode, callOwner, callName, callDescriptor, false)
        if (extra) visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String")
        if (lineAt == "return") line()
        visitInsn(Opcodes.ARETURN)
    }

    @Test
    fun `the proxy kotlinc writes is marked STATIC_FORWARDER, so the shapes below differ from it by one condition`() {
        assertEquals(GeneratedBy.STATIC_FORWARDER, mark(proxy()))
    }

    @Test
    fun `a proxy with its one line entry at the return instruction is marked`() {
        assertEquals(GeneratedBy.STATIC_FORWARDER, mark(proxy { proxyBody(lineAt = "return") }))
    }

    @Test
    fun `a proxy with a line entry at its first or middle instruction is the adopter's and is not marked`() {
        assertEquals(GeneratedBy.NONE, mark(proxy { proxyBody(lineAt = "first") }))
        assertEquals(GeneratedBy.NONE, mark(proxy { proxyBody(lineAt = "call") }))
    }

    @Test
    fun `a proxy through a named companion is marked`() {
        val named = Field("Factory", "L$CLASS\$Factory;", Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_STATIC)
        assertEquals(
            GeneratedBy.STATIC_FORWARDER,
            mark(proxy(fields = listOf(named)) { proxyBody(field = named, callOwner = "$CLASS\$Factory") }),
        )
    }

    @Test
    fun `a proxy whose field name differs from the companion class it calls is not marked`() {
        val misnamed = Field("Instance", "L$COMPANION;", Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_STATIC)
        assertEquals(GeneratedBy.NONE, mark(proxy(fields = listOf(misnamed)) { proxyBody(field = misnamed) }))
    }

    @Test
    fun `a proxy whose field is missing, not static, not final or of another type is not marked`() {
        assertEquals(GeneratedBy.NONE, mark(proxy(fields = emptyList())))
        listOf(Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC).forEach { access ->
            val field = Field("Companion", "L$COMPANION;", access)
            assertEquals(GeneratedBy.NONE, mark(proxy(fields = listOf(field))), "access $access")
        }
        val other = Field("Companion", "Lcom/example/target/Other;", Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_STATIC)
        assertEquals(GeneratedBy.NONE, mark(proxy(fields = listOf(other)) { proxyBody(field = other) }))
    }

    @Test
    fun `a proxy that calls another name, descriptor, owner or opcode is not marked`() {
        assertEquals(GeneratedBy.NONE, mark(proxy { proxyBody(callName = "other") }))
        assertEquals(GeneratedBy.NONE, mark(proxy { proxyBody(callDescriptor = "(Ljava/lang/String;)Ljava/lang/Object;") }))
        assertEquals(GeneratedBy.NONE, mark(proxy { proxyBody(callOwner = "$CLASS\$Another") }))
        assertEquals(GeneratedBy.NONE, mark(proxy { proxyBody(callOpcode = Opcodes.INVOKESTATIC) }))
    }

    @Test
    fun `a proxy that loads the receiver from another place, reads a non-static field or has one instruction more is not marked`() {
        assertEquals(GeneratedBy.NONE, mark(proxy { proxyBody(argumentSlot = 1) }))
        assertEquals(GeneratedBy.NONE, mark(proxy { proxyBody(fieldOpcode = Opcodes.GETFIELD) }))
        assertEquals(GeneratedBy.NONE, mark(proxy { proxyBody(extra = true) }))
    }

    @Test
    fun `a proxy with a null check is not marked`() {
        assertEquals(
            GeneratedBy.NONE,
            mark(
                proxy {
                    visitVarInsn(Opcodes.ALOAD, 0)
                    visitLdcInsn("text")
                    visitMethodInsn(Opcodes.INVOKESTATIC, INTRINSICS_OWNER, "checkNotNullParameter", CHECK, false)
                    proxyBody()
                },
            ),
        )
    }

    @Test
    fun `an instance method, a non-public or synthetic static or a class without metadata is not marked as a proxy`() {
        assertEquals(GeneratedBy.NONE, mark(proxy(access = Opcodes.ACC_PUBLIC)))
        assertEquals(GeneratedBy.NONE, mark(proxy(access = Opcodes.ACC_STATIC)))
        assertEquals(GeneratedBy.NONE, mark(proxy(access = Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC)))
        assertEquals(GeneratedBy.NONE, mark(proxy(metadata = false)))
    }

    private fun callerOf(
        owner: String,
        name: String,
        opcode: Int,
        receiver: Boolean,
    ): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, "com/example/target/Caller", null, "java/lang/Object", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "call", "(L$CLASS;Ljava/lang/String;)Ljava/lang/String;", null, null).apply {
            visitCode()
            if (receiver) visitVarInsn(Opcodes.ALOAD, 0)
            visitVarInsn(Opcodes.ALOAD, 1)
            visitMethodInsn(opcode, owner, name, DESCRIPTOR, false)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun callsOf(
        shape: Shape,
        caller: ByteArray,
        owner: String,
    ): List<CallEdge> {
        val classes = mapOf(owner to build(shape))
        return BranchSiteAnalyzer
            .analyze(caller, { name -> classes[name] }, listOf("com.example.target"), emptyList()) { _, _ -> true }
            .callsOf("call", "(L$CLASS;Ljava/lang/String;)Ljava/lang/String;")
    }

    @Test
    fun `a call to a delegation forwarder records the edge to the delegate's method`() {
        val edges = callsOf(delegation(), callerOf(CLASS, "describe", Opcodes.INVOKEVIRTUAL, receiver = true), CLASS)

        assertEquals(true, CallEdge(DELEGATE.replace('/', '.'), "describe", DESCRIPTOR, virtual = true) in edges, edges.toString())
        assertEquals(false, edges.any { it.methodName == "describe" && it.className == CLASS.replace('/', '.') }, edges.toString())
    }

    @Test
    fun `a call to a method that looks like a delegation forwarder but carries a line number stays an edge to it`() {
        val edges = callsOf(delegation(lineNumber = true), callerOf(CLASS, "describe", Opcodes.INVOKEVIRTUAL, receiver = true), CLASS)

        assertEquals(true, CallEdge(CLASS.replace('/', '.'), "describe", DESCRIPTOR, virtual = true) in edges, edges.toString())
        assertEquals(false, edges.any { it.className == DELEGATE.replace('/', '.') }, edges.toString())
    }

    @Test
    fun `a call to a JvmStatic proxy records the edge to the companion's method`() {
        val edges = callsOf(proxy(), callerOf(CLASS, "describe", Opcodes.INVOKESTATIC, receiver = false), CLASS)

        assertEquals(true, CallEdge(COMPANION.replace('/', '.'), "describe", DESCRIPTOR, virtual = true) in edges, edges.toString())
        assertEquals(false, edges.any { it.methodName == "describe" && it.className == CLASS.replace('/', '.') }, edges.toString())
    }
}
