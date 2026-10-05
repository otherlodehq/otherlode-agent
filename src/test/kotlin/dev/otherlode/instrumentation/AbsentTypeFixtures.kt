package dev.otherlode.instrumentation

import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.Opcodes

/**
 * Classes written with ASM that name a type no loader provides, so a test can weave and run a class
 * whose optional dependency is absent. Nothing here ever defines [ABSENT_PREFIX]`...`.
 */
internal object AbsentTypeFixtures {
    /** The package of every fixture. */
    const val PACKAGE = "com.example.withabsent"

    /** The internal-name prefix of the types that exist nowhere. */
    const val ABSENT_PREFIX = "com/example/absent/"

    private fun ClassWriter.constructor(superName: String) {
        val mv = visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        mv.visitCode()
        val start = Label()
        mv.visitLabel(start)
        mv.visitLineNumber(3, start)
        mv.visitVarInsn(Opcodes.ALOAD, 0)
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false)
        mv.visitInsn(Opcodes.RETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    /**
     * A class implementing `IntUnaryOperator` with a field `Missing<String, Integer>`, a method
     * returning `Missing<?, ?>` and one returning `Wrapped<?>`. `Missing` and `Wrapped` are absent,
     * and each is given one count of type arguments. `applyAsInt` returns 1 for a positive argument
     * and 2 otherwise; a test calls it through the interface, so the JVM never resolves the absent types.
     */
    fun holder(internalName: String): ByteArray {
        val missing = "L${ABSENT_PREFIX}Missing;"
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", arrayOf("java/util/function/IntUnaryOperator"))
        cw.visitSource("Holder.java", null)
        cw
            .visitField(
                Opcodes.ACC_PUBLIC,
                "field",
                missing,
                "L${ABSENT_PREFIX}Missing<Ljava/lang/String;Ljava/lang/Integer;>;",
                null,
            ).visitEnd()
        cw.constructor("java/lang/Object")
        val get = cw.visitMethod(Opcodes.ACC_PUBLIC, "get", "()$missing", "()L${ABSENT_PREFIX}Missing<**>;", null)
        get.visitCode()
        val getLine = Label()
        get.visitLabel(getLine)
        get.visitLineNumber(5, getLine)
        get.visitInsn(Opcodes.ACONST_NULL)
        get.visitInsn(Opcodes.ARETURN)
        get.visitMaxs(0, 0)
        get.visitEnd()
        val wrap = cw.visitMethod(Opcodes.ACC_PUBLIC, "wrap", "()L${ABSENT_PREFIX}Wrapped;", "()L${ABSENT_PREFIX}Wrapped<*>;", null)
        wrap.visitCode()
        val wrapLine = Label()
        wrap.visitLabel(wrapLine)
        wrap.visitLineNumber(6, wrapLine)
        wrap.visitInsn(Opcodes.ACONST_NULL)
        wrap.visitInsn(Opcodes.ARETURN)
        wrap.visitMaxs(0, 0)
        wrap.visitEnd()
        val apply = cw.visitMethod(Opcodes.ACC_PUBLIC, "applyAsInt", "(I)I", null, null)
        apply.visitCode()
        val applyLine = Label()
        apply.visitLabel(applyLine)
        apply.visitLineNumber(7, applyLine)
        val other = Label()
        apply.visitVarInsn(Opcodes.ILOAD, 1)
        apply.visitJumpInsn(Opcodes.IFLE, other)
        apply.visitInsn(Opcodes.ICONST_1)
        apply.visitInsn(Opcodes.IRETURN)
        apply.visitLabel(other)
        apply.visitInsn(Opcodes.ICONST_2)
        apply.visitInsn(Opcodes.IRETURN)
        apply.visitMaxs(0, 0)
        apply.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    /** A class named [internalName] extending [superName] and implementing [interfaces], with a constructor and nothing else. */
    fun subtype(
        internalName: String,
        superName: String,
        vararg interfaces: String,
    ): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, superName, interfaces.takeIf { it.isNotEmpty() })
        cw.visitSource("Subtype.java", null)
        cw.constructor(superName)
        cw.visitEnd()
        return cw.toByteArray()
    }

    /** A class named [internalName] extending `Object` whose class-level `Signature` attribute is [signature]. */
    fun signed(
        internalName: String,
        signature: String,
    ): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, signature, "java/lang/Object", null)
        cw.visitSource("Signed.java", null)
        cw.constructor("java/lang/Object")
        cw.visitEnd()
        return cw.toByteArray()
    }

    /** The classes of [classMerge]: a base, two subclasses and a class whose method returns either. */
    class MergeClasses(
        val base: Pair<String, ByteArray>,
        val first: Pair<String, ByteArray>,
        val second: Pair<String, ByteArray>,
        val chooser: Pair<String, ByteArray>,
    )

    /**
     * `Base`, `A` and `B` (both extending `Base`) and `C`, whose `static Base pick(int)` returns a new
     * `A` for a positive argument and a new `B` otherwise. The frame at the return merges `A` and `B`,
     * which is `Base` and not `Object`; the verifier rejects `Object` there. With [returnType]
     * `java/lang/Object` the method returns that instead, so no frame of the class file names `Base`.
     */
    fun classMerge(
        packageInternal: String,
        returnType: String = "$packageInternal/Base",
    ): MergeClasses {
        val base = "$packageInternal/Base"
        val a = "$packageInternal/A"
        val b = "$packageInternal/B"
        val c = "$packageInternal/C"

        fun sub(
            name: String,
            superName: String,
        ): ByteArray {
            val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
            cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, superName, null)
            cw.constructor(superName)
            cw.visitEnd()
            return cw.toByteArray()
        }
        val cw =
            object : ClassWriter(COMPUTE_FRAMES) {
                override fun getCommonSuperClass(
                    type1: String,
                    type2: String,
                ): String = if (type1 == type2) type1 else returnType
            }
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, c, null, "java/lang/Object", null)
        cw.visitSource("C.java", null)
        cw.constructor("java/lang/Object")
        val pick = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "pick", "(I)L$returnType;", null, null)
        pick.visitCode()
        val line = Label()
        pick.visitLabel(line)
        pick.visitLineNumber(4, line)
        val second = Label()
        val end = Label()
        pick.visitVarInsn(Opcodes.ILOAD, 0)
        pick.visitJumpInsn(Opcodes.IFLE, second)
        pick.visitTypeInsn(Opcodes.NEW, a)
        pick.visitInsn(Opcodes.DUP)
        pick.visitMethodInsn(Opcodes.INVOKESPECIAL, a, "<init>", "()V", false)
        pick.visitJumpInsn(Opcodes.GOTO, end)
        pick.visitLabel(second)
        pick.visitTypeInsn(Opcodes.NEW, b)
        pick.visitInsn(Opcodes.DUP)
        pick.visitMethodInsn(Opcodes.INVOKESPECIAL, b, "<init>", "()V", false)
        pick.visitLabel(end)
        pick.visitInsn(Opcodes.ARETURN)
        pick.visitMaxs(0, 0)
        pick.visitEnd()
        cw.visitEnd()
        val baseWriter = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        baseWriter.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, base, null, "java/lang/Object", null)
        baseWriter.constructor("java/lang/Object")
        baseWriter.visitEnd()
        return MergeClasses(base to baseWriter.toByteArray(), a to sub(a, base), b to sub(b, base), c to cw.toByteArray())
    }

    /** One `InnerClasses` entry of [innerClassFixture]: [name], its outer class (null for a local or anonymous one), its simple name and flags. */
    class InnerEntry(
        val name: String,
        val outerName: String?,
        val innerName: String?,
        val access: Int,
    )

    /**
     * A class named [internalName] extending `Object` with the class [access] flags, the `InnerClasses`
     * [entries] and, for a local class, an `EnclosingMethod` of [enclosing] (class, method name,
     * descriptor), and a constructor.
     */
    fun innerClassFixture(
        internalName: String,
        access: Int,
        entries: List<InnerEntry>,
        enclosing: Triple<String, String, String>? = null,
    ): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V1_8, access, internalName, null, "java/lang/Object", null)
        cw.visitSource("Inner.java", null)
        enclosing?.let { (owner, method, descriptor) -> cw.visitOuterClass(owner, method, descriptor) }
        for (entry in entries) cw.visitInnerClass(entry.name, entry.outerName, entry.innerName, entry.access)
        cw.constructor("java/lang/Object")
        cw.visitEnd()
        return cw.toByteArray()
    }

    /**
     * A package-private class named [internalName] with a public `foo()` and a public
     * `take(Missing)Ret`, which names two absent types, and a constructor. A public subclass that does
     * not override them is the shape for which ByteBuddy adds visibility bridges.
     */
    fun packagePrivateParent(internalName: String): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V1_8, Opcodes.ACC_SUPER, internalName, null, "java/lang/Object", null)
        cw.visitSource("Parent.java", null)
        cw.constructor("java/lang/Object")
        val foo = cw.visitMethod(Opcodes.ACC_PUBLIC, "foo", "()V", null, null)
        foo.visitCode()
        foo.visitInsn(Opcodes.RETURN)
        foo.visitMaxs(0, 0)
        foo.visitEnd()
        val take = cw.visitMethod(Opcodes.ACC_PUBLIC, "take", "(L${ABSENT_PREFIX}Missing;)L${ABSENT_PREFIX}Ret;", null, null)
        take.visitCode()
        take.visitInsn(Opcodes.ACONST_NULL)
        take.visitInsn(Opcodes.ARETURN)
        take.visitMaxs(0, 0)
        take.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    /**
     * A public interface named [internalName] with only abstract methods, so there is nothing to
     * probe, whose class `Signature` is [signature].
     */
    fun abstractInterface(
        internalName: String,
        signature: String?,
    ): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE,
            internalName,
            signature,
            "java/lang/Object",
            null,
        )
        cw.visitSource("Abstract.java", null)
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "find", "()Ljava/lang/Object;", null, null).visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    /** A public interface named [internalName] extending [superInterface], with a static method so that there is something to probe. */
    fun interfaceExtending(
        internalName: String,
        superInterface: String,
    ): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE,
            internalName,
            null,
            "java/lang/Object",
            arrayOf(superInterface),
        )
        cw.visitSource("Extending.java", null)
        val make = cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "make", "()I", null, null)
        make.visitCode()
        make.visitInsn(Opcodes.ICONST_1)
        make.visitInsn(Opcodes.IRETURN)
        make.visitMaxs(0, 0)
        make.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }
}
