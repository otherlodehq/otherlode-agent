package dev.otherlode.instrumentation

import dev.otherlode.config.AgentConfig
import dev.otherlode.export.ProbeKind
import dev.otherlode.export.ProbeLocation
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.export.UnreadShape
import dev.otherlode.instrumentation.branch.OutlineMutations
import dev.otherlode.instrumentation.branch.UnreadCause
import dev.otherlode.instrumentation.branch.UnreadShapeCounts
import dev.otherlode.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Label
import net.bytebuddy.jar.asm.Opcodes
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves through the real transform that the outlines of ADR 0054 no source produces reach the
 * manifest: a coroutine state machine jump and a string switch collision side as the unread shape of
 * a branch outcome, and a multi-file facade's method as the unread shape of its probes. Each class is
 * real compiler output with one instruction changed, or a facade built by hand, and is defined only
 * after the agent is installed.
 */
class UnreadOutlineInstrumentationTest {
    private companion object {
        const val PACKAGE = "com.example.target"
        const val FACADE = "$PACKAGE.AsmFacade"
    }

    @TempDir
    lateinit var tempDir: Path

    private var installedTransformer: ResettableClassFileTransformer? = null
    private var installedOtherlode: OtherlodeInstrumentation? = null

    @AfterTest
    fun tearDown() {
        installedTransformer?.let { installedOtherlode?.uninstall(ByteBuddyAgent.install(), it) }
        installedTransformer = null
        installedOtherlode = null
    }

    private class Run(
        val probes: List<ProbeLocation>,
        val counts: UnreadShapeCounts,
    )

    /** Loads [className] from a loader whose first root holds [bytes] in place of the compiled class. */
    private fun load(
        className: String,
        bytes: ByteArray,
    ): Run {
        val root = tempDir.resolve("classes-${System.nanoTime()}").toFile()
        val file = File(root, className.replace('.', '/') + ".class")
        file.parentFile.mkdirs()
        file.writeBytes(bytes)
        val registry = ProbeRegistry()
        val counts = UnreadShapeCounts()
        val otherlode = OtherlodeInstrumentation(AgentConfig.parse("includePackages=$PACKAGE"), registry, unreadShapeCounts = counts)
        installedOtherlode = otherlode
        installedTransformer = otherlode.install(ByteBuddyAgent.install())
        val loader =
            FixtureClassLoader(
                arrayOf(root, File("build/classes/kotlin/test"), File("build/classes/java/test")).map { it.toURI().toURL() }.toTypedArray(),
                javaClass.classLoader,
            )
        Class.forName(className, true, loader)
        val probes =
            registry.manifest(ResourceAttributes("test", null, "instance-1", null, "run-1")).probes.filter {
                it.className ==
                    className
            }
        return Run(probes, counts)
    }

    private fun method(
        run: Run,
        name: String,
    ): ProbeLocation = run.probes.single { it.kind == ProbeKind.METHOD && it.methodName == name }

    @Test
    fun `a coroutine state machine jump in a shape the agent does not read reaches the manifest as an unread outcome`() {
        val real = File("build/classes/kotlin/test/com/example/target/CoroutineTargetKt.class").readBytes()
        val bytes = OutlineMutations.rewrite(real, "twoPoints", OutlineMutations::nopBeforeSwitch)

        val run = load("$PACKAGE.CoroutineTargetKt", bytes)

        val sites = method(run, "twoPoints").branchSites
        assertEquals(
            listOf(List(4) { UnreadShape.COROUTINE_MACHINERY }, List(2) { UnreadShape.NONE }),
            sites.map { site -> site.outcomes.map { it.unreadShape } },
        )
        assertEquals(UnreadShape.NONE, method(run, "twoPoints").unreadShape, "the method itself is the adopter's")
        assertEquals(6, run.probes.count { it.kind == ProbeKind.BRANCH && it.methodName == "twoPoints" }, "the unread outcomes are probed")
        // adopterMarkerCompare's own compare with the public marker stays the adopter's.
        assertEquals(4L, run.counts.outcomeCountOf(UnreadShape.COROUTINE_MACHINERY), "twoPoints' four")
        assertEquals(4L, run.counts.outcomeTotal())
        assertEquals(0L, run.counts.total(), "no method is unread")
    }

    @Test
    fun `the collision side of an unread string switch reaches the manifest as an unread outcome that is still probed`() {
        val real = File("build/classes/kotlin/test/com/example/target/SwitchTarget.class").readBytes()
        val bytes = OutlineMutations.rewrite(real, "stringWhen", OutlineMutations::nopAfterFirstStore)

        val run = load("$PACKAGE.SwitchTarget", bytes)

        val unread = method(run, "stringWhen").branchSites.flatMap { site -> site.outcomes.filter { it.unreadShape != UnreadShape.NONE } }
        assertEquals(4, unread.size)
        assertTrue(unread.all { it.unreadShape == UnreadShape.SWITCH_LOWERING })
        val branchIndexes = run.probes.filter { it.kind == ProbeKind.BRANCH && it.methodName == "stringWhen" }.map { it.branchIndex }
        assertTrue(unread.all { it.branchIndex in branchIndexes }, "each unread outcome has its own probe")
        assertEquals(4L, run.counts.outcomeCountOf(UnreadShape.SWITCH_LOWERING))
    }

    @Test
    fun `a multi-file facade method that is not a forwarder reaches the manifest as an unread shape, its branch probes included`() {
        val run = load(FACADE, facadeWithBranch())

        assertEquals(UnreadShape.MULTIFILE_FACADE, method(run, "f").unreadShape)
        val branches = run.probes.filter { it.kind == ProbeKind.BRANCH && it.methodName == "f" }
        assertEquals(2, branches.size)
        assertTrue(branches.all { it.unreadShape == UnreadShape.MULTIFILE_FACADE })
        assertEquals(1L, run.counts.countOf(UnreadShape.MULTIFILE_FACADE))
        assertEquals(1L, run.counts.countOf(UnreadCause.UNREAD_STRUCTURE))
        assertEquals(0L, run.counts.outcomeTotal(), "the outcomes carry the method's shape, not one of their own")
        assertEquals(emptyList(), run.counts.unreadReleases())
    }

    /** A `kotlin.Metadata` `k = 4` class whose `f(I)I` holds a conditional of its own. */
    private fun facadeWithBranch(): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V1_5, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, FACADE.replace('.', '/'), null, "java/lang/Object", null)
        writer.visitAnnotation("Lkotlin/Metadata;", true).apply {
            visit("k", 4)
            visitEnd()
        }
        val mv = writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "f", "(I)I", null, null)
        mv.visitCode()
        val zero = Label()
        mv.visitVarInsn(Opcodes.ILOAD, 0)
        mv.visitJumpInsn(Opcodes.IFEQ, zero)
        mv.visitInsn(Opcodes.ICONST_1)
        mv.visitInsn(Opcodes.IRETURN)
        mv.visitLabel(zero)
        mv.visitInsn(Opcodes.ICONST_0)
        mv.visitInsn(Opcodes.IRETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }
}
