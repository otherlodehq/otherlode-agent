package dev.otherlode.instrumentation

import net.bytebuddy.agent.ByteBuddyAgent
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BootstrapHolderTest {
    @Test
    fun `installs the holder onto the bootstrap loader from the embedded jar, and is idempotent`() {
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val log = File.createTempFile("bootstrap-holder", ".log")
        log.deleteOnExit()
        val process =
            ProcessBuilder(
                java,
                "-Djdk.attach.allowAttachSelf=true",
                "-cp",
                System.getProperty("java.class.path"),
                InstallInFreshJvm::class.java.name,
            ).redirectErrorStream(true).redirectOutput(log).start()
        val exited = process.waitFor(60, TimeUnit.SECONDS)
        if (!exited) process.destroyForcibly()
        val output = log.readText()
        assertTrue(exited, "the JVM exited. Output:\n$output")
        assertEquals(0, process.exitValue(), "the JVM installed the holder. Output:\n$output")
        assertTrue("before=false after=true holder=null endpoints=null" in output, "Output:\n$output")
    }

    @Test
    fun `a missing embedded jar is reported as an install failure rather than a later NoClassDefFoundError`() {
        // install() returns early once another test has appended the holder to this JVM.
        val exception =
            assertFailsWith<BootstrapInstallException> {
                BootstrapHolder.readEmbeddedJar("META-INF/otherlode/does-not-exist.jar")
            }
        assertTrue("does-not-exist.jar" in exception.message.orEmpty())
    }
}

object InstallInFreshJvm {
    @JvmStatic
    fun main(args: Array<String>) {
        val before = BootstrapHolder.isInstalled()
        val instrumentation = ByteBuddyAgent.install()
        BootstrapHolder.install(instrumentation)
        BootstrapHolder.install(instrumentation)
        val holder = Class.forName(BootstrapHolder.HOLDER_CLASS_NAME, false, null).classLoader
        val endpoints = Class.forName(BootstrapHolder.ENDPOINTS_CLASS_NAME, false, null).classLoader
        println("before=$before after=${BootstrapHolder.isInstalled()} holder=$holder endpoints=$endpoints")
    }
}
