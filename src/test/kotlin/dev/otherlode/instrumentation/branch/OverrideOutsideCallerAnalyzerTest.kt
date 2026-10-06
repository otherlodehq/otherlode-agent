package dev.otherlode.instrumentation.branch

import dev.otherlode.instrumentation.JvmDefaultDisableFixtures
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassVisitor
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.MethodVisitor
import net.bytebuddy.jar.asm.Opcodes
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves which out-of-scope type [BranchSiteAnalyzer] names for a method that overrides one of its
 * methods, on javac and kotlinc output and on class files written here for the shapes a compiler
 * refuses. The fixtures put `com.example.target.outsidecaller` in scope and its `external` package
 * and the class `samepkg.Hidden` out of it.
 */
class OverrideOutsideCallerAnalyzerTest {
    private val includePackages = listOf("com.example.target.outsidecaller", JvmDefaultDisableFixtures.PACKAGE_PREFIX.removeSuffix("."))
    private val excludePackages =
        listOf("com.example.target.outsidecaller.external", "com.example.target.outsidecaller.samepkg.Hidden")

    private val roots =
        listOf(
            "build/classes/java/test",
            "build/classes/kotlin/test",
            JvmDefaultDisableFixtures.outputDir.path,
        )

    private val lookup: (String) -> ByteArray? = { internalName ->
        val dottedName = internalName.replace('/', '.')
        roots
            .asSequence()
            .map { ClassFileLocator.ForFolder(File(it)).locate(dottedName) }
            .firstOrNull { it.isResolved }
            ?.resolve()
            ?: javaClass.classLoader.getResourceAsStream("$internalName.class")?.use { it.readBytes() }
    }

    private fun bytesOf(internalName: String): ByteArray = checkNotNull(lookup(internalName)) { "no class file for $internalName" }

    private fun analyze(
        bytes: ByteArray,
        lookup: (String) -> ByteArray? = this.lookup,
        tableCache: BranchSiteAnalyzer.CrossClassTableCache? = null,
        methodFilter: (String, String) -> Boolean = { _, _ -> true },
    ): BranchSiteAnalyzer.Analysis =
        BranchSiteAnalyzer.analyze(
            bytes,
            lookup,
            includePackages,
            excludePackages,
            tableCache = tableCache,
            outsideCallers = true,
            methodFilter = methodFilter,
        )

    private fun analyze(
        simpleName: String,
        methodFilter: (String, String) -> Boolean = { _, _ -> true },
    ) = analyze(bytesOf("com/example/target/outsidecaller/$simpleName"), methodFilter = methodFilter)

    @Test
    fun `a class implementing Runnable overrides java lang Runnable through run, and its helper overrides nothing`() {
        val analysis = analyze("RunnableImpl")

        assertEquals("java.lang.Runnable", analysis.overriddenOutsideTypeOf("run", "()V"))
        assertNull(analysis.overriddenOutsideTypeOf("helper", "()V"))
        assertNull(analysis.overriddenOutsideTypeOf("<init>", "()V"))
    }

    @Test
    fun `toString, equals and hashCode override java lang Object`() {
        val analysis = analyze("ObjectOverrides")

        assertEquals("java.lang.Object", analysis.overriddenOutsideTypeOf("toString", "()Ljava/lang/String;"))
        assertEquals("java.lang.Object", analysis.overriddenOutsideTypeOf("equals", "(Ljava/lang/Object;)Z"))
        assertEquals("java.lang.Object", analysis.overriddenOutsideTypeOf("hashCode", "()I"))
        assertNull(analysis.overriddenOutsideTypeOf("unrelated", "()I"))
    }

    @Test
    fun `a static method and an instance method beside a private one match no declaration`() {
        val analysis = analyze("Hiding")

        assertNull(analysis.overriddenOutsideTypeOf("util", "()V"), "a static method never overrides")
        assertNull(analysis.overriddenOutsideTypeOf("secret", "()V"), "a private declaration is not overridden")
    }

    @Test
    fun `a private method and a static method whose name and descriptor match an out-of-scope method are not marked`() {
        val bytes = classWithShapes()

        val analysis = analyze(bytes)

        assertNull(analysis.overriddenOutsideTypeOf("run", "()V"), "private")
        assertNull(analysis.overriddenOutsideTypeOf("ping", "()V"), "static")
        assertEquals(
            "java.lang.Object",
            analysis.overriddenOutsideTypeOf("toString", "()Ljava/lang/String;"),
            "the control: an instance method",
        )
    }

    @Test
    fun `a method overriding only an in-scope method is not marked`() {
        val analysis = analyze("InScopeChild")

        assertNull(analysis.overriddenOutsideTypeOf("work", "()V"))
    }

    @Test
    fun `an in-scope superclass that implements an out-of-scope interface passes it on to its subclass`() {
        val analysis = analyze("ConcreteEventHandler")

        assertEquals(
            "com.example.target.outsidecaller.external.ExternalCallback",
            analysis.overriddenOutsideTypeOf("onEvent", "(Ljava/lang/String;)V"),
        )
        assertNull(analysis.overriddenOutsideTypeOf("tick", "()V"), "EventBase is in scope")
    }

    @Test
    fun `a method overriding an out-of-scope superclass two in-scope levels up is marked`() {
        val analysis = analyze("LeafOfMiddle")

        assertEquals("com.example.target.outsidecaller.external.ExternalBase", analysis.overriddenOutsideTypeOf("ping", "()V"))
        assertNull(analysis.overriddenOutsideTypeOf("other", "()V"), "MiddleBase declares it and is in scope")
    }

    @Test
    fun `an in-scope interface that extends Runnable passes it on to its implementer`() {
        val analysis = analyze("HandlerImpl")

        assertEquals("java.lang.Runnable", analysis.overriddenOutsideTypeOf("run", "()V"))
    }

    @Test
    fun `the nearest out-of-scope declaration wins over one further out`() {
        val analysis = analyze("RawList")

        assertEquals("java.util.AbstractList", analysis.overriddenOutsideTypeOf("get", "(I)Ljava/lang/Object;"))
        assertEquals("java.util.AbstractCollection", analysis.overriddenOutsideTypeOf("size", "()I"))
    }

    @Test
    fun `a package-private declaration in another package is not overridden`() {
        val analysis = analyze("DifferentPackageChild")

        assertNull(analysis.overriddenOutsideTypeOf("quiet", "()V"))
    }

    @Test
    fun `a package-private declaration in the same package name is overridden`() {
        val analysis = analyze(bytesOf("com/example/target/outsidecaller/samepkg/SamePackageChild"))

        assertEquals("com.example.target.outsidecaller.samepkg.Hidden", analysis.overriddenOutsideTypeOf("quiet", "()V"))
    }

    @Test
    fun `a generic override is marked through its same-class bridge, and the bridge itself is not asked about`() {
        val analysis = analyze("Version") { name, descriptor -> !(name == "compareTo" && descriptor == "(Ljava/lang/Object;)I") }

        assertEquals("java.lang.Comparable", analysis.overriddenOutsideTypeOf("compareTo", "(Lcom/example/target/outsidecaller/Version;)I"))
        assertNull(analysis.overriddenOutsideTypeOf("compareTo", "(Ljava/lang/Object;)I"))
        assertNull(analysis.overriddenOutsideTypeOf("nothing", "()I"))
    }

    @Test
    fun `an anonymous class implementing Runnable is marked like any other class`() {
        val analysis = analyze("Anonymous\$1")

        assertEquals("java.lang.Runnable", analysis.overriddenOutsideTypeOf("run", "()V"))
    }

    @Test
    fun `a Kotlin object expression implementing Runnable is marked`() {
        val analysis = analyze("KotlinObjectExpression\$make\$1")

        assertEquals("java.lang.Runnable", analysis.overriddenOutsideTypeOf("run", "()V"))
    }

    @Test
    fun `a class implementing an interface of the JDK's HttpHandler shape names it`() {
        val analysis = analyze("ServerHandler")

        assertEquals(
            "com.sun.net.httpserver.HttpHandler",
            analysis.overriddenOutsideTypeOf("handle", "(Lcom/sun/net/httpserver/HttpExchange;)V"),
        )
    }

    @Test
    fun `under jvm-default=disable a DefaultImpls method is marked only when its interface method overrides an out-of-scope one`() {
        val analysis =
            analyze(bytesOf("${JvmDefaultDisableFixtures.PACKAGE_PREFIX.replace('.', '/')}DisabledRunnableInterface\$DefaultImpls"))

        val self = "L${JvmDefaultDisableFixtures.PACKAGE_PREFIX.replace('.', '/')}DisabledRunnableInterface;"
        assertEquals("java.lang.Runnable", analysis.overriddenOutsideTypeOf("run", "($self)V"))
        assertNull(analysis.overriddenOutsideTypeOf("plain", "($self)I"))
    }

    @Test
    fun `a DefaultImpls method of an interface that overrides nothing outside is not marked`() {
        val analysis =
            analyze(bytesOf("${JvmDefaultDisableFixtures.PACKAGE_PREFIX.replace('.', '/')}DisabledDefaultInterface\$DefaultImpls"))

        val self = "L${JvmDefaultDisableFixtures.PACKAGE_PREFIX.replace('.', '/')}DisabledDefaultInterface;"
        assertNull(analysis.overriddenOutsideTypeOf("withBody", "($self)I"))
        assertNull(analysis.overriddenOutsideTypeOf("getLabel", "($self)Ljava/lang/String;"))
    }

    @Test
    fun `a supertype whose class file cannot be read stops that branch and marks nothing from it`() {
        val unreadable = "com/example/target/outsidecaller/external/ExternalCallback"
        val blind: (String) -> ByteArray? = { if (it == unreadable) null else lookup(it) }

        val handler = analyze(bytesOf("com/example/target/outsidecaller/ConcreteEventHandler"), blind)
        val runnable = analyze(bytesOf("com/example/target/outsidecaller/RunnableImpl"), blind)

        assertNull(handler.overriddenOutsideTypeOf("onEvent", "(Ljava/lang/String;)V"))
        assertEquals("java.lang.Runnable", runnable.overriddenOutsideTypeOf("run", "()V"), "an unrelated branch still walks")
    }

    @Test
    fun `an unreadable branch does not hide a match in the branch after it`() {
        val unreadable = "com/example/target/outsidecaller/EventBase"
        val blind: (String) -> ByteArray? = { if (it == unreadable) null else lookup(it) }

        val analysis = analyze(bytesOf("com/example/target/outsidecaller/ConcreteEventHandler"), blind)

        assertEquals(
            "com.example.target.outsidecaller.external.ExternalCallback",
            analysis.overriddenOutsideTypeOf("onEvent", "(Ljava/lang/String;)V"),
        )
    }

    @Test
    fun `a supertype cycle ends the walk`() {
        val bytes = cyclicPair()
        val cyclic: (String) -> ByteArray? = {
            if (it == "$CYCLE_A") {
                bytes.first
            } else if (it == "$CYCLE_B") {
                bytes.second
            } else {
                lookup(it)
            }
        }

        val analysis = analyze(bytes.first, cyclic)

        assertNull(analysis.overriddenOutsideTypeOf("m", "()V"))
    }

    @Test
    fun `every type is read once per cache, unreadable ones included`() {
        val reads = mutableMapOf<String, Int>()
        val counting: (String) -> ByteArray? = { name ->
            reads.merge(name, 1, Int::plus)
            if (name == "com/example/target/outsidecaller/external/ExternalCallback") null else lookup(name)
        }
        val cache = BranchSiteAnalyzer.CrossClassTableCache(64)

        analyze(bytesOf("com/example/target/outsidecaller/RawList"), counting, cache)
        analyze(bytesOf("com/example/target/outsidecaller/ConcreteEventHandler"), counting, cache)
        analyze(bytesOf("com/example/target/outsidecaller/ConcreteEventHandler"), counting, cache)
        val again = reads.toMap()
        analyze(bytesOf("com/example/target/outsidecaller/RawList"), counting, cache)

        assertEquals(1, reads["java/util/AbstractList"])
        assertEquals(1, reads["java/lang/Object"], "shared by both walks")
        assertEquals(1, reads["com/example/target/outsidecaller/external/ExternalCallback"], "a miss is cached too")
        assertEquals(again, reads, "a third analysis reads nothing it has read")
    }

    @Test
    fun `a cache holds at most its bound of headers`() {
        val cache = BranchSiteAnalyzer.CrossClassTableCache(3)

        analyze(bytesOf("com/example/target/outsidecaller/RawList"), lookup, cache)

        assertEquals(3, cache.headerCount)
    }

    @Test
    fun `a protected method of an out-of-scope superclass is overridden`() {
        val analysis = analyze("HookChild")

        assertEquals("com.example.target.outsidecaller.external.ExternalBase", analysis.overriddenOutsideTypeOf("hook", "()V"))
    }

    @Test
    fun `a Kotlin bridge that calls a method of another name passes its type to that method`() {
        val analysis = analyze("KotlinSequence")

        assertEquals("java.lang.CharSequence", analysis.overriddenOutsideTypeOf("getLength", "()I"))
        assertEquals("java.lang.CharSequence", analysis.overriddenOutsideTypeOf("get", "(I)C"))
        assertEquals(
            "java.lang.CharSequence",
            analysis.overriddenOutsideTypeOf("subSequence", "(II)Ljava/lang/CharSequence;"),
        )
    }

    @Test
    fun `a nested class named DefaultImpls that is not a Kotlin default-body class takes the ordinary walk`() {
        val analysis = analyze("NestedDefaultImplsHost\$DefaultImpls")

        assertEquals("java.lang.Runnable", analysis.overriddenOutsideTypeOf("run", "()V"))
    }

    @Test
    fun `a value class bridge that unboxes before its call passes its type to the method it calls`() {
        val bytes = bytesOf("com/example/target/outsidecaller/ValueId")
        val mangled = instanceMethodNames(bytes).single { (name, descriptor) -> name.startsWith("compareTo-") && descriptor == "(I)I" }

        val analysis = analyze(bytes)

        assertEquals("java.lang.Comparable", analysis.overriddenOutsideTypeOf(mangled.first, mangled.second))
        assertNull(analysis.overriddenOutsideTypeOf("unbox-impl", "()I"))
    }

    @Test
    fun `a static method in a DefaultImpls class whose outer type is a class is not read as a default body`() {
        val analysis = analyze("StaticDefaultImplsHost\$DefaultImpls")

        assertNull(analysis.overriddenOutsideTypeOf("run", "(Lcom/example/target/outsidecaller/StaticDefaultImplsHost;)V"))
    }

    @Test
    fun `under jvm-default=disable a default body for a generic method is not marked, a recorded gap`() {
        val prefix = JvmDefaultDisableFixtures.PACKAGE_PREFIX.replace('.', '/')
        val analysis = analyze(bytesOf("${prefix}DisabledFunctionInterface\$DefaultImpls"))

        assertNull(analysis.overriddenOutsideTypeOf("invoke", "(L${prefix}DisabledFunctionInterface;Ljava/lang/String;)V"))
    }

    @Test
    fun `out-of-scope supertypes are read through the out-of-scope lookup and in-scope ones through the shared lookup`() {
        val shared = mutableListOf<String>()
        val outOfScope = mutableListOf<String>()
        val bytes = bytesOf("com/example/target/outsidecaller/LeafOfMiddle")

        val analysis =
            BranchSiteAnalyzer.analyzeThrough(
                ClassReader(bytes),
                bytes,
                { name -> lookup(name).also { shared += name } },
                includePackages,
                excludePackages,
                null,
                emptySet(),
                { null },
                null,
                outsideCallers = true,
                outOfScopeLookup = { name -> lookup(name).also { outOfScope += name } },
            ) { _, _ -> true }

        assertEquals("com.example.target.outsidecaller.external.ExternalBase", analysis.overriddenOutsideTypeOf("ping", "()V"))
        assertTrue("com/example/target/outsidecaller/MiddleBase" in shared)
        assertTrue("com/example/target/outsidecaller/external/ExternalBase" in outOfScope)
        assertTrue("java/lang/Object" in outOfScope)
        assertTrue(outOfScope.none { it.startsWith("com/example/target/outsidecaller/") && !it.contains("/external/") })
        assertTrue("com/example/target/outsidecaller/external/ExternalBase" !in shared)
    }

    @Test
    fun `an analysis that does not ask for outside callers reads no supertype`() {
        val reads = mutableListOf<String>()
        val counting: (String) -> ByteArray? = { name ->
            reads += name
            lookup(name)
        }

        val analysis =
            BranchSiteAnalyzer.analyze(
                bytesOf("com/example/target/outsidecaller/RunnableImpl"),
                counting,
                includePackages,
                excludePackages,
            ) { _, _ -> true }

        assertNull(analysis.overriddenOutsideTypeOf("run", "()V"))
        assertEquals(emptyList(), reads.filter { it == "java/lang/Runnable" })
    }

    /** The non-static methods [bytes] declares, as name and descriptor. */
    private fun instanceMethodNames(bytes: ByteArray): List<Pair<String, String>> {
        val methods = mutableListOf<Pair<String, String>>()
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor? {
                    if (access and Opcodes.ACC_STATIC == 0) methods += name to descriptor
                    return null
                }
            },
            ClassReader.SKIP_CODE,
        )
        return methods
    }

    /** `Shapes` implements `Runnable` and extends `ExternalBase`, with a private `run()V` and a static `ping()V`. */
    private fun classWithShapes(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V17,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER,
            "com/example/target/outsidecaller/Shapes",
            null,
            "com/example/target/outsidecaller/external/ExternalBase",
            arrayOf("java/lang/Runnable"),
        )
        for (
        (access, name, descriptor) in
        listOf(
            Triple(Opcodes.ACC_PRIVATE, "run", "()V"),
            Triple(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "ping", "()V"),
        )
        ) {
            writer.visitMethod(access, name, descriptor, null, null).apply {
                visitCode()
                visitInsn(Opcodes.RETURN)
                visitMaxs(0, 1)
                visitEnd()
            }
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC, "toString", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitInsn(Opcodes.ACONST_NULL)
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    /** Two classes in scope that name each other as superclass and interface. */
    private fun cyclicPair(): Pair<ByteArray, ByteArray> {
        fun write(
            name: String,
            other: String,
        ): ByteArray {
            val writer = ClassWriter(0)
            writer.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE,
                name,
                null,
                "java/lang/Object",
                arrayOf(other),
            )
            writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "m", "()V", null, null).visitEnd()
            writer.visitEnd()
            return writer.toByteArray()
        }
        return write(CYCLE_A, CYCLE_B) to write(CYCLE_B, CYCLE_A)
    }

    private companion object {
        const val CYCLE_A = "com/example/target/outsidecaller/CycleA"
        const val CYCLE_B = "com/example/target/outsidecaller/CycleB"
    }
}
