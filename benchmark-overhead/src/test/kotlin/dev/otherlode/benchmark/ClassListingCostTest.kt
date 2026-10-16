package dev.otherlode.benchmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClassListingCostTest {
    @Test
    fun `reads the cost from the program's line among other output`() {
        val out = "warming\nLISTING_COST coldCpuMs=82.186 coldWallMs=86.726 warmCpuMs=24.175 classes=122\n"
        assertEquals(ClassListingCost(82.186, 86.726, 24.175, 122), ClassListingCost.parse(out))
    }

    @Test
    fun `a missing line is an invalid run that quotes the output`() {
        val e = assertThrows(InvalidRunException::class.java) { ClassListingCost.parse("Exception in thread main") }
        assertTrue("no LISTING_COST line" in e.message!!, e.message)
        assertTrue("Exception in thread main" in e.message!!, e.message)
    }

    @Test
    fun `a null listing leaves no line and is an invalid run`() {
        val out = "ClassLister.list() returned null for /app/petclinic.jar\n"
        assertThrows(InvalidRunException::class.java) { ClassListingCost.parse(out) }
    }

    @Test
    fun `the properties carry the four values under their metadata names`() {
        val props = ClassListingCost(82.5, 90.0, 24.0, 122).properties()
        assertEquals(
            mapOf(
                "classListingColdCpuMs" to "82.5",
                "classListingColdWallMs" to "90.0",
                "classListingWarmCpuMs" to "24.0",
                "classListingClasses" to "122",
            ),
            props,
        )
    }

    @Test
    fun `the command passes the config's include rules and no excludes`() {
        val cmd = listingCostCommand(Config.CEILING)
        assertEquals(listOf("/app/petclinic.jar", "org.springframework", ""), cmd.takeLast(3))
        val script = cmd[2]
        assertTrue(script.startsWith("javac -cp /app/otherlode-agent.jar "), script)
        assertTrue("exec java -cp /app/otherlode-agent.jar:/tmp/listing-cost ListingCost \"$@\"" in script, script)
        assertTrue("ListingCost.java\"" !in script && " /app/ListingCost.java &&" in script, script)
    }

    @Test
    fun `the section states the cold and warm cost and the class count`() {
        val section = classListingSection(ClassListingCost(82.186, 86.726, 24.175, 122)) { "0" }
        assertEquals(
            "\n### Class listing\n\nCold: 82.186 ms CPU (86.726 ms wall) on PetClinic's pinned core(s) 0, " +
                "listing 122 classes under the config's rules. Warm median of four more: 24.175 ms CPU.\n",
            section,
        )
    }

    @Test
    fun `no measurement leaves the section out`() {
        assertEquals("", classListingSection(null) { error("a missing measurement reads no cpuset") })
    }
}
