package dev.otherlode.instrumentation

import dev.otherlode.instrumentation.branch.BranchDropReason
import dev.otherlode.instrumentation.branch.BranchSiteAnalyzer
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the edges of ADR 0079's rules on forged class files: every rule needs its enhancer's
 * marker interface, the line-table guards keep a hand-written look-alike, and a check that cannot
 * read what it needs leaves the method the adopter's.
 */
class JpaEnhancementRulesTest {
    private companion object {
        const val OWNER = "forged/Entity"
        const val ENTITY_BEAN = "io/ebean/bean/EntityBean"
        const val PERSISTENCE_WEAVED = "org/eclipse/persistence/internal/weaving/PersistenceWeaved"
        const val PERSISTENCE_CAPABLE = "org/apache/openjpa/enhance/PersistenceCapable"
        const val TO_STRING_BUILDER = "io/ebean/bean/ToStringBuilder"
        const val STATE_MANAGER = "Lorg/apache/openjpa/enhance/StateManager;"
    }

    /** One forged method: its [name] and [descriptor], whether it has a line-number table, and its body. */
    private class Method(
        val name: String,
        val descriptor: String,
        val access: Int = Opcodes.ACC_PUBLIC,
        val line: Boolean = false,
        val body: (MethodVisitor) -> Unit = { it.visitInsn(Opcodes.RETURN) },
    )

    private fun forge(
        interfaces: List<String>,
        methods: List<Method>,
        fields: Map<String, String> = emptyMap(),
    ): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, OWNER, null, "java/lang/Object", interfaces.toTypedArray())
        for ((name, descriptor) in fields) writer.visitField(Opcodes.ACC_PRIVATE, name, descriptor, null, null).visitEnd()
        for (method in methods) {
            val visitor = writer.visitMethod(method.access, method.name, method.descriptor, null, null)
            visitor.visitCode()
            val start = Label()
            visitor.visitLabel(start)
            if (method.line) visitor.visitLineNumber(10, start)
            method.body(visitor)
            visitor.visitMaxs(4, 4)
            visitor.visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun unprobedNames(enhancement: JpaEnhancement): Set<String> = enhancement.unprobed.map { it.first + it.second }.toSet()

    @Test
    fun `no enhancer's marker, no rule applies`() {
        val enhancement =
            JpaEnhancement.of(
                forge(
                    listOf("java/io/Serializable"),
                    listOf(
                        Method("_ebean_x", "()V"),
                        Method("_persistence_x", "()V"),
                        Method("pcIsDirty", "()Z"),
                        Method("<init>", "(Lio/ebean/bean/EntityBean;)V"),
                        Method("getId", "()J"),
                        Method("pcgetId", "()J", line = true),
                    ),
                ),
            )

        assertSame(JpaEnhancement.NONE, enhancement)
    }

    @Test
    fun `one library's marker does not enable another library's rules`() {
        val ebean = JpaEnhancement.of(forge(listOf(ENTITY_BEAN), listOf(Method("_persistence_x", "()V"), Method("pcIsDirty", "()Z"))))
        val weaved = JpaEnhancement.of(forge(listOf(PERSISTENCE_WEAVED), listOf(Method("_ebean_x", "()V"), Method("pcIsDirty", "()Z"))))
        val capable =
            JpaEnhancement.of(
                forge(listOf(PERSISTENCE_CAPABLE), listOf(Method("_ebean_x", "()V"), Method("_persistence_x", "()V"))),
            )

        assertEquals(emptySet(), ebean.unprobed)
        assertEquals(emptySet(), weaved.unprobed)
        assertEquals(emptySet(), capable.unprobed)
    }

    @Test
    fun `ebean's members are the enhancer's whether or not they are synthetic or have a line table`() {
        val toBuilder: (MethodVisitor) -> Unit = {
            it.visitTypeInsn(Opcodes.NEW, TO_STRING_BUILDER)
            it.visitInsn(Opcodes.DUP)
            it.visitVarInsn(Opcodes.ALOAD, 0)
            it.visitVarInsn(Opcodes.ALOAD, 1)
            it.visitMethodInsn(Opcodes.INVOKEVIRTUAL, OWNER, "toString", "(Lio/ebean/bean/ToStringBuilder;)V", false)
            it.visitInsn(Opcodes.RETURN)
        }
        val enhancement =
            JpaEnhancement.of(
                forge(
                    listOf(ENTITY_BEAN),
                    listOf(
                        Method("_ebean_getField", "(I)Ljava/lang/Object;", line = true),
                        Method("_ebean_newInstance", "()Ljava/lang/Object;"),
                        Method("<init>", "(Lio/ebean/bean/EntityBean;)V", Opcodes.ACC_PROTECTED, line = true),
                        Method("<init>", "()V", line = true),
                        Method("toString", "(Lio/ebean/bean/ToStringBuilder;)V", line = true),
                        Method("toString", "()Ljava/lang/String;", line = true, body = toBuilder),
                        Method("getName", "()Ljava/lang/String;", line = true),
                    ),
                ),
            )

        assertEquals(
            setOf(
                "_ebean_getField(I)Ljava/lang/Object;",
                "_ebean_newInstance()Ljava/lang/Object;",
                "<init>(Lio/ebean/bean/EntityBean;)V",
                "toString(Lio/ebean/bean/ToStringBuilder;)V",
                "toString()Ljava/lang/String;",
            ),
            unprobedNames(enhancement),
        )
    }

    @Test
    fun `an ebean toString is the enhancer's only when it builds a ToStringBuilder first and calls the overload on itself`() {
        fun enhancementWith(body: (MethodVisitor) -> Unit) =
            JpaEnhancement.of(forge(listOf(ENTITY_BEAN), listOf(Method("toString", "()Ljava/lang/String;", line = true, body = body))))

        val noBuilder =
            enhancementWith {
                it.visitVarInsn(Opcodes.ALOAD, 0)
                it.visitMethodInsn(Opcodes.INVOKEVIRTUAL, OWNER, "toString", "(Lio/ebean/bean/ToStringBuilder;)V", false)
                it.visitInsn(Opcodes.RETURN)
            }
        val notFirst =
            enhancementWith {
                it.visitInsn(Opcodes.ICONST_0)
                it.visitTypeInsn(Opcodes.NEW, TO_STRING_BUILDER)
                it.visitMethodInsn(Opcodes.INVOKEVIRTUAL, OWNER, "toString", "(Lio/ebean/bean/ToStringBuilder;)V", false)
                it.visitInsn(Opcodes.RETURN)
            }
        val noCall =
            enhancementWith {
                it.visitTypeInsn(Opcodes.NEW, TO_STRING_BUILDER)
                it.visitInsn(Opcodes.RETURN)
            }
        val otherOwner =
            enhancementWith {
                it.visitTypeInsn(Opcodes.NEW, TO_STRING_BUILDER)
                it.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "forged/Other", "toString", "(Lio/ebean/bean/ToStringBuilder;)V", false)
                it.visitInsn(Opcodes.RETURN)
            }

        assertEquals(emptySet(), noBuilder.unprobed)
        assertEquals(emptySet(), notFirst.unprobed)
        assertEquals(emptySet(), noCall.unprobed)
        assertEquals(emptySet(), otherOwner.unprobed)
    }

    @Test
    fun `eclipselink's _persistence_ method is the enhancer's only without a line-number table`() {
        val enhancement =
            JpaEnhancement.of(
                forge(
                    listOf(PERSISTENCE_WEAVED),
                    listOf(
                        Method("_persistence_get", "(Ljava/lang/String;)Ljava/lang/Object;"),
                        Method("_persistence_userWritten", "()V", line = true),
                        Method("<init>", "(Lorg/eclipse/persistence/internal/descriptors/PersistenceObject;)V"),
                        Method("<init>", "()V", line = true),
                        Method("getId", "()J", line = true),
                    ),
                ),
            )

        assertEquals(
            setOf(
                "_persistence_get(Ljava/lang/String;)Ljava/lang/Object;",
                "<init>(Lorg/eclipse/persistence/internal/descriptors/PersistenceObject;)V",
            ),
            unprobedNames(enhancement),
        )
    }

    @Test
    fun `openjpa's listed methods are the enhancer's only without a line table and with the listed descriptor`() {
        val enhancement =
            JpaEnhancement.of(
                forge(
                    listOf(PERSISTENCE_CAPABLE),
                    listOf(
                        Method("pcIsDirty", "()Z"),
                        Method("pcReplaceField", "(I)V"),
                        Method("pcCopyField", "(L$OWNER;I)V", Opcodes.ACC_PROTECTED),
                        Method("pcNewInstance", "(${STATE_MANAGER}Z)Lorg/apache/openjpa/enhance/PersistenceCapable;"),
                        Method("pcGetDetachedState", "()Ljava/lang/Object;"),
                        Method("pcIsDeleted", "()Z", line = true),
                        Method("pcIsNew", "()I"),
                        Method("pcRate", "()Ljava/lang/String;", line = true),
                        Method("pcRate2", "()Ljava/lang/String;"),
                        Method("pcReadUnmanaged", "(Ljava/io/ObjectInput;)V"),
                        Method("readExternal", "(Ljava/io/ObjectInput;)V"),
                        Method("writeExternal", "(Ljava/io/ObjectOutput;)V"),
                    ),
                ),
            )

        assertEquals(
            setOf(
                "pcIsDirty()Z",
                "pcReplaceField(I)V",
                "pcCopyField(Lforged/Entity;I)V",
                "pcNewInstance(${STATE_MANAGER}Z)Lorg/apache/openjpa/enhance/PersistenceCapable;",
                "pcGetDetachedState()Ljava/lang/Object;",
                "pcReadUnmanaged(Ljava/io/ObjectInput;)V",
                "readExternal(Ljava/io/ObjectInput;)V",
            ),
            unprobedNames(enhancement),
            "writeExternal goes with pcWriteUnmanaged, and a hand-written pcRate keeps its name",
        )
    }

    @Test
    fun `openjpa's serialization methods are the enhancer's only without a line table`() {
        val enhancement =
            JpaEnhancement.of(
                forge(
                    listOf(PERSISTENCE_CAPABLE),
                    listOf(
                        Method("writeObject", "(Ljava/io/ObjectOutputStream;)V", Opcodes.ACC_PRIVATE),
                        Method("readObject", "(Ljava/io/ObjectInputStream;)V", Opcodes.ACC_PRIVATE, line = true),
                        Method("readExternalFields", "(Ljava/io/ObjectInput;)V", Opcodes.ACC_PROTECTED),
                        Method("writeExternalFields", "(Ljava/io/ObjectOutput;)V", Opcodes.ACC_PROTECTED),
                        Method("readExternal", "(Ljava/io/ObjectInput;)V"),
                        Method("writeExternal", "(Ljava/io/ObjectOutput;)V"),
                    ),
                ),
            )

        assertEquals(
            setOf(
                "writeObject(Ljava/io/ObjectOutputStream;)V",
                "readExternalFields(Ljava/io/ObjectInput;)V",
                "writeExternalFields(Ljava/io/ObjectOutput;)V",
            ),
            unprobedNames(enhancement),
            "a readObject with a line table is the adopter's, and readExternal and writeExternal need pcReadUnmanaged and pcWriteUnmanaged",
        )
    }

    @Test
    fun `ebean's equals and hashCode are the enhancer's only when they call its identity method`() {
        val callsIdentity: (MethodVisitor) -> Unit = {
            it.visitVarInsn(Opcodes.ALOAD, 0)
            it.visitMethodInsn(Opcodes.INVOKESPECIAL, OWNER, "_ebean_getIdentity", "()Ljava/lang/Object;", false)
            it.visitInsn(Opcodes.POP)
            it.visitInsn(Opcodes.RETURN)
        }
        val enhanced =
            JpaEnhancement.of(
                forge(
                    listOf(ENTITY_BEAN),
                    listOf(
                        Method("equals", "(Ljava/lang/Object;)Z", line = true, body = callsIdentity),
                        Method("hashCode", "()I", line = true),
                    ),
                ),
            )
        val hashCodeOnly =
            JpaEnhancement.of(forge(listOf(ENTITY_BEAN), listOf(Method("hashCode", "()I", line = true, body = callsIdentity))))
        val callsOther: (MethodVisitor) -> Unit = {
            it.visitVarInsn(Opcodes.ALOAD, 0)
            it.visitMethodInsn(Opcodes.INVOKEVIRTUAL, OWNER, "getId", "()Ljava/lang/Object;", false)
            it.visitVarInsn(Opcodes.ALOAD, 0)
            it.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "forged/Other", "_ebean_getIdentity", "()Ljava/lang/Object;", false)
            it.visitInsn(Opcodes.POP2)
            it.visitInsn(Opcodes.RETURN)
        }
        val adopters =
            JpaEnhancement.of(
                forge(
                    listOf(ENTITY_BEAN),
                    listOf(
                        Method("equals", "(Ljava/lang/Object;)Z", line = true, body = callsOther),
                        Method("hashCode", "()I", line = true, body = callsOther),
                    ),
                ),
            )

        assertEquals(setOf("equals(Ljava/lang/Object;)Z"), unprobedNames(enhanced))
        assertEquals(setOf("hashCode()I"), unprobedNames(hashCodeOnly))
        assertEquals(emptySet(), adopters.unprobed, "a call of another method, or of another class's identity method")
    }

    @Test
    fun `openjpa's field accessors are the enhancer's only static, for a declared field, with the owner first`() {
        val staticAccess = Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL
        val enhancement =
            JpaEnhancement.of(
                forge(
                    listOf(PERSISTENCE_CAPABLE),
                    listOf(
                        Method("pcGetid", "(L$OWNER;)Ljava/lang/Long;", staticAccess),
                        Method("pcSetid", "(L$OWNER;Ljava/lang/Long;)V", staticAccess),
                        Method("pcGetmissing", "(L$OWNER;)Ljava/lang/Long;", staticAccess),
                        Method("pcGetname", "(L$OWNER;)Ljava/lang/Long;", staticAccess),
                        Method("pcSetname", "(L$OWNER;Ljava/lang/Long;)V", staticAccess),
                        Method("pcGetnote", "()Ljava/lang/String;", Opcodes.ACC_PUBLIC),
                        Method("pcSetid", "(Lforged/Other;Ljava/lang/Long;)V", staticAccess),
                        Method("pcGetnote", "(L$OWNER;)Ljava/lang/String;", staticAccess, line = true),
                        Method("pcGetcount", "(L$OWNER;)Ljava/lang/Integer;", Opcodes.ACC_PUBLIC),
                    ),
                    fields =
                        mapOf(
                            "id" to "Ljava/lang/Long;",
                            "name" to "Ljava/lang/String;",
                            "note" to "Ljava/lang/String;",
                            "count" to "Ljava/lang/Integer;",
                        ),
                ),
            )

        assertEquals(
            setOf("pcGetid(Lforged/Entity;)Ljava/lang/Long;", "pcSetid(Lforged/Entity;Ljava/lang/Long;)V"),
            unprobedNames(enhancement),
        )
    }

    @Test
    fun `an openjpa wrapper is the enhancer's when its pc twin has a line table, and the twin reports under its name`() {
        val enhancement =
            JpaEnhancement.of(
                forge(
                    listOf(PERSISTENCE_CAPABLE),
                    listOf(
                        Method("getId", "()Ljava/lang/Long;"),
                        Method("pcgetId", "()Ljava/lang/Long;", Opcodes.ACC_PROTECTED, line = true),
                        Method("setName", "(Ljava/lang/String;)V"),
                        Method("pcsetName", "(Ljava/lang/String;)V", Opcodes.ACC_PROTECTED),
                        Method("getOther", "()Ljava/lang/Long;"),
                        Method("pcgetOther", "()Ljava/lang/String;", line = true),
                        Method("getLined", "()Ljava/lang/Long;", line = true),
                        Method("pcgetLined", "()Ljava/lang/Long;", line = true),
                    ),
                ),
            )

        assertEquals(setOf("getId()Ljava/lang/Long;"), unprobedNames(enhancement))
        assertEquals(mapOf(("pcgetId" to "()Ljava/lang/Long;") to "getId"), enhancement.reportedNames)
        assertEquals("getId", enhancement.reportedName("pcgetId", "()Ljava/lang/Long;"))
        assertEquals("pcsetName", enhancement.reportedName("pcsetName", "(Ljava/lang/String;)V"))
    }

    @Test
    fun `the weaving's operands are the ones EclipseLink adds and no others`() {
        fun field(name: String) = JpaEnhancement.WovenOperand.FieldRead(name)

        fun call(
            name: String,
            descriptor: String = "()Z",
        ) = JpaEnhancement.WovenOperand.Call(name, descriptor)
        val other = JpaEnhancement.WovenOperand.Other

        assertTrue(JpaEnhancement.readsWeavedState(field("_persistence_listener"), other))
        assertTrue(JpaEnhancement.readsWeavedState(call("_persistence_isAttributeFetched"), other))
        assertTrue(JpaEnhancement.readsWeavedState(call("isInstantiated"), field("_persistence_account_vh")))
        assertEquals(false, JpaEnhancement.readsWeavedState(field("listener"), other))
        assertEquals(false, JpaEnhancement.readsWeavedState(call("isInstantiated"), field("account")))
        assertEquals(false, JpaEnhancement.readsWeavedState(call("isInstantiated"), field("_persistence_listener")))
        assertEquals(false, JpaEnhancement.readsWeavedState(call("isInstantiated"), other))
        assertEquals(false, JpaEnhancement.readsWeavedState(call("isInstantiated", "()I"), field("_persistence_account_vh")))
        assertEquals(false, JpaEnhancement.readsWeavedState(call("lookup"), field("_persistence_account_vh")))
        assertEquals(false, JpaEnhancement.readsWeavedState(other, field("_persistence_listener")))
    }

    /** A method whose first jump comes before its line-number table, then a jump after it, in a class with [interfaces]. */
    private fun weavedBytes(
        interfaces: List<String>,
        lined: Boolean,
        prologue: (MethodVisitor, Label) -> Unit,
    ): ByteArray {
        val method =
            Method("setId", "(Ljava/lang/Long;)V", line = false) { visitor ->
                val first = Label()
                val second = Label()
                prologue(visitor, first)
                visitor.visitLabel(first)
                if (lined) visitor.visitLineNumber(20, first)
                visitor.visitVarInsn(Opcodes.ALOAD, 1)
                visitor.visitJumpInsn(Opcodes.IFNULL, second)
                visitor.visitInsn(Opcodes.NOP)
                visitor.visitLabel(second)
                visitor.visitInsn(Opcodes.RETURN)
            }
        return forge(interfaces, listOf(method), fields = mapOf("_persistence_listener" to "Ljava/beans/PropertyChangeListener;"))
    }

    private fun droppedOrdinals(bytes: ByteArray): List<Boolean> =
        BranchSiteAnalyzer
            .analyze(bytes) { _, _ -> true }
            .sites
            .map { it.dropReason == BranchDropReason.JPA_WEAVING }

    private val readsListener: (MethodVisitor, Label) -> Unit = { visitor, target ->
        visitor.visitVarInsn(Opcodes.ALOAD, 0)
        visitor.visitFieldInsn(Opcodes.GETFIELD, OWNER, "_persistence_listener", "Ljava/beans/PropertyChangeListener;")
        visitor.visitJumpInsn(Opcodes.IFNULL, target)
    }

    @Test
    fun `a check of the weaving's state before the first line entry is dropped and the adopter's check after it is kept`() {
        assertEquals(listOf(true, false), droppedOrdinals(weavedBytes(listOf(PERSISTENCE_WEAVED), lined = true, readsListener)))
    }

    @Test
    fun `the same check in a class without the weaving's marker is the adopter's`() {
        assertEquals(listOf(false, false), droppedOrdinals(weavedBytes(emptyList(), lined = true, readsListener)))
    }

    @Test
    fun `a method with no line-number table has no first entry to sit before`() {
        assertEquals(listOf(false, false), droppedOrdinals(weavedBytes(listOf(PERSISTENCE_WEAVED), lined = false, readsListener)))
    }

    @Test
    fun `a check of a field the weaving did not add stays the adopter's`() {
        val plain: (MethodVisitor, Label) -> Unit = { visitor, target ->
            visitor.visitVarInsn(Opcodes.ALOAD, 0)
            visitor.visitFieldInsn(Opcodes.GETFIELD, OWNER, "listener", "Ljava/lang/Object;")
            visitor.visitJumpInsn(Opcodes.IFNULL, target)
        }

        assertEquals(listOf(false, false), droppedOrdinals(weavedBytes(listOf(PERSISTENCE_WEAVED), lined = true, plain)))
    }

    @Test
    fun `a comparison of two operands is never read as woven`() {
        val twoOperands: (MethodVisitor, Label) -> Unit = { visitor, target ->
            visitor.visitVarInsn(Opcodes.ALOAD, 0)
            visitor.visitFieldInsn(Opcodes.GETFIELD, OWNER, "_persistence_listener", "Ljava/beans/PropertyChangeListener;")
            visitor.visitVarInsn(Opcodes.ALOAD, 1)
            visitor.visitJumpInsn(Opcodes.IF_ACMPEQ, target)
        }

        assertEquals(listOf(false, false), droppedOrdinals(weavedBytes(listOf(PERSISTENCE_WEAVED), lined = true, twoOperands)))
    }

    @Test
    fun `the result of a _persistence_ call before the first line entry is woven`() {
        val call: (MethodVisitor, Label) -> Unit = { visitor, target ->
            visitor.visitVarInsn(Opcodes.ALOAD, 0)
            visitor.visitLdcInsn("id")
            visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, OWNER, "_persistence_isAttributeFetched", "(Ljava/lang/String;)Z", false)
            visitor.visitJumpInsn(Opcodes.IFNE, target)
        }

        assertEquals(listOf(true, false), droppedOrdinals(weavedBytes(listOf(PERSISTENCE_WEAVED), lined = true, call)))
    }

    @Test
    fun `a moved openjpa accessor that calls itself records no edge to its own reported name`() {
        val bytes =
            forge(
                listOf(PERSISTENCE_CAPABLE),
                listOf(
                    Method("getDepth", "()V") { visitor ->
                        visitor.visitVarInsn(Opcodes.ALOAD, 0)
                        visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, OWNER, "pcgetDepth", "()V", false)
                        visitor.visitInsn(Opcodes.RETURN)
                    },
                    Method("pcgetDepth", "()V", Opcodes.ACC_PROTECTED, line = true) { visitor ->
                        visitor.visitVarInsn(Opcodes.ALOAD, 0)
                        visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, OWNER, "pcgetDepth", "()V", false)
                        visitor.visitInsn(Opcodes.RETURN)
                    },
                ),
            )

        val analysis = BranchSiteAnalyzer.analyze(bytes) { _, _ -> true }

        assertEquals("getDepth", analysis.reportedNameOf("pcgetDepth", "()V"))
        assertEquals(emptyList(), analysis.callsOf("pcgetDepth", "()V"))
    }

    @Test
    fun `a method openjpa wrapped as the class loaded is one whose pc twin only the received bytes declare`() {
        val classFile =
            forge(
                emptyList(),
                listOf(
                    Method("getId", "()Ljava/lang/Long;", line = true),
                    Method("rate", "()I", line = true),
                    Method("pcrate", "()I", line = true),
                    Method("getName", "()Ljava/lang/String;", line = true),
                ),
            )
        val enhanced =
            listOf(
                Method("getId", "()Ljava/lang/Long;"),
                Method("pcgetId", "()Ljava/lang/Long;", Opcodes.ACC_PROTECTED),
                Method("rate", "()I", line = true),
                Method("pcrate", "()I", line = true),
                Method("getName", "()Ljava/lang/String;"),
                Method("pcgetName", "()Ljava/lang/Integer;", Opcodes.ACC_PROTECTED),
            )

        assertEquals(
            setOf("getId" to "()Ljava/lang/Long;"),
            JpaEnhancement.loadTimeWrappers(classFile, forge(listOf(PERSISTENCE_CAPABLE), enhanced)),
            "a pc twin the class file already declares, or one with another descriptor, is no wrapper",
        )
        assertEquals(emptySet(), JpaEnhancement.loadTimeWrappers(classFile, forge(emptyList(), enhanced)), "no marker, no wrapper")
    }

    @Test
    fun `an enhancer method is not eligible for the analyser even when the caller's filter accepts it`() {
        val bytes =
            forge(
                listOf(ENTITY_BEAN),
                listOf(
                    Method("_ebean_get_id", "()V", line = true) { visitor ->
                        val label = Label()
                        visitor.visitVarInsn(Opcodes.ALOAD, 0)
                        visitor.visitJumpInsn(Opcodes.IFNULL, label)
                        visitor.visitLabel(label)
                        visitor.visitInsn(Opcodes.RETURN)
                    },
                ),
            )

        val analysis = BranchSiteAnalyzer.analyze(bytes) { _, _ -> true }

        assertEquals(emptyList(), analysis.sites)
        assertEquals(emptyList(), analysis.callsOf("_ebean_get_id", "()V"))
    }
}
