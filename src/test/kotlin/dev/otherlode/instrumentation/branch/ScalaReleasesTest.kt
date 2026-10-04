package dev.otherlode.instrumentation.branch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScalaReleasesTest {
    private fun header(tooling: String) = BrokenScalaFixtures.tastyHeader(tooling)

    @Test
    fun `the release is the tooling string of the tasty header without its prefix`() {
        assertEquals("3.9.0", ScalaReleases.releaseOf(header("Scala 3.9.0")))
        assertEquals("3.10.0-RC3", ScalaReleases.releaseOf(header("Scala 3.10.0-RC3")))
        assertEquals("other 1.0", ScalaReleases.releaseOf(header("other 1.0")))
    }

    @Test
    fun `bytes that are not a tasty header have no release`() {
        assertNull(ScalaReleases.releaseOf(byteArrayOf()))
        assertNull(ScalaReleases.releaseOf(byteArrayOf(1, 2, 3, 4, 5)))
        assertNull(ScalaReleases.releaseOf(header("Scala 3.9.0").copyOf(8)), "a header cut inside its string")
    }

    @Test
    fun `a name is tried first and then each enclosing name cut at a dollar`() {
        val asked = mutableListOf<String>()

        val release =
            ScalaReleases.releaseOf("com/acme/Foo\$Bar\$\$anon\$1", { path ->
                asked += path
                if (path == "com/acme/Foo.tasty") header("Scala 3.3.4") else null
            })

        assertEquals("3.3.4", release)
        assertEquals(
            listOf(
                "com/acme/Foo\$Bar\$\$anon\$1.tasty",
                "com/acme/Foo\$Bar\$\$anon.tasty",
                "com/acme/Foo\$Bar\$.tasty",
                "com/acme/Foo\$Bar.tasty",
                "com/acme/Foo.tasty",
            ),
            asked,
        )
    }

    @Test
    fun `a dollar in the package is not an enclosing class`() {
        val asked = mutableListOf<String>()

        assertNull(ScalaReleases.releaseOf("com/ac\$me/Foo", { path -> null.also { asked += path } }))

        assertEquals(listOf("com/ac\$me/Foo.tasty"), asked)
    }

    @Test
    fun `a lookup that throws reads as no tasty`() {
        assertNull(ScalaReleases.releaseOf("com/acme/Foo", { error("unreadable") }))
    }

    @Test
    fun `the read list holds the releases from 3 3 3 to 3 9 0 and no release candidate`() {
        for (release in listOf("3.3.3", "3.3.8", "3.4.0", "3.4.3", "3.5.2", "3.6.4", "3.7.4", "3.8.4", "3.9.0")) {
            assertTrue(ScalaReleases.isRead(release), release)
        }
        assertFalse(ScalaReleases.isRead("3.3.2"))
        assertFalse(ScalaReleases.isRead("3.9.1"))
        assertFalse(ScalaReleases.isRead("3.10.0"))
        assertTrue(ScalaReleases.read.none { '-' in it }, "release candidates are never read")
    }
}
