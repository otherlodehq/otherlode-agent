package dev.otherlode.instrumentation

import net.bytebuddy.asm.AsmVisitorWrapper
import net.bytebuddy.description.field.FieldDescription
import net.bytebuddy.description.field.FieldList
import net.bytebuddy.description.method.MethodList
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.implementation.Implementation
import net.bytebuddy.jar.asm.AnnotationVisitor
import net.bytebuddy.jar.asm.Attribute
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.FieldVisitor
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.jar.asm.RecordComponentVisitor
import net.bytebuddy.jar.asm.TypePath
import net.bytebuddy.pool.TypePool

/**
 * Writes a woven class's header from the bytes the weave started from, so the class says about
 * itself what the unwoven class says.
 *
 * ByteBuddy writes the class header from its description of the type, and a description is not
 * the class file. Where a type it names is a placeholder, or the loader serves no class files, or
 * the description simply drops what the class file records, the header differs: an interface bound
 * becomes a class bound in the generic signature, a member class's `InnerClasses` entry loses its
 * outer class and simple name (so reflection on it throws), and a local class's simple name changes.
 * The method tier adds no auxiliary type and no class-level flag, so nothing ByteBuddy writes here is
 * something the agent needs.
 *
 * What is restored: the access flags, the generic `Signature`, every `InnerClasses` entry in its
 * original order, `EnclosingMethod`, `NestHost`, `NestMembers` and `PermittedSubclasses`. The
 * superclass, the interfaces, the annotations, the source attributes and every member the weave
 * does not define are ByteBuddy's pass-through of the received bytes, and the members the agent adds
 * (the probe field, the accessor methods, the refusal marker) stay.
 */
internal object ClassHeaderRestorer {
    /** The wrapper that gives the written class the header [classBytes] carries. */
    fun wrapper(classBytes: ByteArray): AsmVisitorWrapper {
        val original = HeaderReader.read(classBytes)
        return object : AsmVisitorWrapper.AbstractBase() {
            override fun wrap(
                instrumentedType: TypeDescription,
                classVisitor: ClassVisitor,
                implementationContext: Implementation.Context,
                typePool: TypePool,
                fields: FieldList<FieldDescription.InDefinedShape>,
                methods: MethodList<*>,
                writerFlags: Int,
                readerFlags: Int,
            ): ClassVisitor =
                object : ClassVisitor(Opcodes.ASM9, classVisitor) {
                    private var earlyEmitted = false

                    override fun visit(
                        version: Int,
                        access: Int,
                        name: String?,
                        signature: String?,
                        superName: String?,
                        interfaces: Array<out String>?,
                    ) {
                        super.visit(version, original.access, name, original.signature, superName, interfaces)
                    }

                    // ASM's ClassVisitor contract orders a class's events: source and module, then nest host and
                    // outer class, then annotations and attributes, then members, nest members, permitted subclasses
                    // and inner classes in any order. The early attributes go out before the first event past the
                    // source, the late ones at the end, as ByteBuddy's own writer emits them.
                    private fun emitEarly() {
                        if (earlyEmitted) return
                        earlyEmitted = true
                        original.replayEarly(cv)
                    }

                    override fun visitAnnotation(
                        descriptor: String?,
                        visible: Boolean,
                    ): AnnotationVisitor? {
                        emitEarly()
                        return super.visitAnnotation(descriptor, visible)
                    }

                    override fun visitTypeAnnotation(
                        typeRef: Int,
                        typePath: TypePath?,
                        descriptor: String?,
                        visible: Boolean,
                    ): AnnotationVisitor? {
                        emitEarly()
                        return super.visitTypeAnnotation(typeRef, typePath, descriptor, visible)
                    }

                    override fun visitAttribute(attribute: Attribute?) {
                        emitEarly()
                        super.visitAttribute(attribute)
                    }

                    override fun visitRecordComponent(
                        name: String?,
                        descriptor: String?,
                        signature: String?,
                    ): RecordComponentVisitor? {
                        emitEarly()
                        return super.visitRecordComponent(name, descriptor, signature)
                    }

                    override fun visitField(
                        access: Int,
                        name: String?,
                        descriptor: String?,
                        signature: String?,
                        value: Any?,
                    ): FieldVisitor? {
                        emitEarly()
                        return super.visitField(access, name, descriptor, signature, value)
                    }

                    override fun visitMethod(
                        access: Int,
                        name: String?,
                        descriptor: String?,
                        signature: String?,
                        exceptions: Array<out String>?,
                    ): MethodVisitor? {
                        emitEarly()
                        return super.visitMethod(access, name, descriptor, signature, exceptions)
                    }

                    override fun visitEnd() {
                        emitEarly()
                        original.replayLate(cv)
                        super.visitEnd()
                    }

                    override fun visitNestHost(nestHost: String?) = Unit

                    override fun visitOuterClass(
                        owner: String?,
                        name: String?,
                        descriptor: String?,
                    ) = Unit

                    override fun visitNestMember(nestMember: String?) = Unit

                    override fun visitPermittedSubclass(permittedSubclass: String?) = Unit

                    override fun visitInnerClass(
                        name: String?,
                        outerName: String?,
                        innerName: String?,
                        access: Int,
                    ) = Unit
                }
        }
    }

    private class Header(
        val access: Int,
        val signature: String?,
        private val nestHost: String?,
        private val outer: Triple<String, String?, String?>?,
        private val nestMembers: List<String>,
        private val permittedSubclasses: List<String>,
        private val innerClasses: List<InnerClassEntry>,
    ) {
        /** The attributes ASM takes before annotations: the nest host and the enclosing method. */
        fun replayEarly(visitor: ClassVisitor) {
            nestHost?.let(visitor::visitNestHost)
            outer?.let { (owner, name, descriptor) -> visitor.visitOuterClass(owner, name, descriptor) }
        }

        /** The attributes ASM takes among or after the members: nest members, permitted subclasses, inner classes. */
        fun replayLate(visitor: ClassVisitor) {
            nestMembers.forEach(visitor::visitNestMember)
            permittedSubclasses.forEach(visitor::visitPermittedSubclass)
            innerClasses.forEach { visitor.visitInnerClass(it.name, it.outerName, it.innerName, it.access) }
        }
    }

    private class InnerClassEntry(
        val name: String,
        val outerName: String?,
        val innerName: String?,
        val access: Int,
    )

    private object HeaderReader {
        fun read(classBytes: ByteArray): Header {
            var access = 0
            var signature: String? = null
            var nestHost: String? = null
            var outer: Triple<String, String?, String?>? = null
            val nestMembers = mutableListOf<String>()
            val permitted = mutableListOf<String>()
            val inner = mutableListOf<InnerClassEntry>()
            ClassReader(classBytes).accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visit(
                        version: Int,
                        access0: Int,
                        name: String?,
                        signature0: String?,
                        superName: String?,
                        interfaces: Array<out String>?,
                    ) {
                        access = access0
                        signature = signature0
                    }

                    override fun visitNestHost(nestHost0: String?) {
                        nestHost = nestHost0
                    }

                    override fun visitOuterClass(
                        owner: String,
                        name: String?,
                        descriptor: String?,
                    ) {
                        outer = Triple(owner, name, descriptor)
                    }

                    override fun visitNestMember(nestMember: String) {
                        nestMembers += nestMember
                    }

                    override fun visitPermittedSubclass(permittedSubclass: String) {
                        permitted += permittedSubclass
                    }

                    override fun visitInnerClass(
                        name: String,
                        outerName: String?,
                        innerName: String?,
                        access0: Int,
                    ) {
                        inner += InnerClassEntry(name, outerName, innerName, access0)
                    }
                },
                ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
            )
            return Header(access, signature, nestHost, outer, nestMembers, permitted, inner)
        }
    }
}
