package dev.otherlode.export

import dev.otherlode.Agent
import dev.otherlode.config.AgentConfig
import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentVersionTest {
    private val shadedJar = File(checkNotNull(System.getProperty("otherlode.agent.shadedJar")) { "otherlode.agent.shadedJar is not set" })
    private val expectedVersion = checkNotNull(System.getProperty("otherlode.agent.version")) { "otherlode.agent.version is not set" }

    @Test
    fun `a class loaded from a directory has no version, so the run resource carries an empty one`() {
        assertEquals("", implementationVersionOf(Agent::class.java))
        assertEquals("", ResourceAttributes.forNewRun(AgentConfig.parse("serviceName=checkout")).agentVersion)
    }

    @Test
    fun `a class loaded from a jar with an Implementation-Version reports it`() {
        assertTrue(implementationVersionOf(kotlin.Unit::class.java).isNotEmpty())
    }

    @Test
    fun `the shaded agent jar's manifest names the project version`() {
        JarFile(shadedJar).use { jar ->
            assertEquals(expectedVersion, jar.manifest.mainAttributes.getValue("Implementation-Version"))
        }
    }

    @Test
    fun `the agent class read from the shaded jar reports the manifest version`() {
        URLClassLoader(arrayOf(shadedJar.toURI().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            val agent = Class.forName(Agent::class.java.name, false, loader)

            assertEquals(expectedVersion, implementationVersionOf(agent))
        }
    }
}
