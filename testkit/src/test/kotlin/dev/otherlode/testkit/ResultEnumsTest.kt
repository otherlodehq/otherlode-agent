package dev.otherlode.testkit

import kotlin.test.Test
import kotlin.test.assertEquals
import dev.otherlode.export.DependencyDiscoverySource as WireDependencyDiscoverySource
import dev.otherlode.export.EndpointDiscoverySource as WireEndpointDiscoverySource
import dev.otherlode.export.GeneratedBy as WireGeneratedBy
import dev.otherlode.export.ProbeKind as WireProbeKind
import dev.otherlode.export.RoutineKind as WireRoutineKind
import dev.otherlode.export.UnreadShape as WireUnreadShape

class ResultEnumsTest {
    @Test
    fun `ProbeKind mirrors every wire value in order and maps back`() {
        assertEquals(WireProbeKind.entries.map { it.name }, ProbeKind.entries.map { it.name })
        for (wire in WireProbeKind.entries) assertEquals(wire, wire.toTestkit().toWire())
    }

    @Test
    fun `GeneratedBy mirrors every wire value but NONE, in order, and maps back`() {
        assertEquals(WireGeneratedBy.entries.map { it.name } - "NONE", GeneratedBy.entries.map { it.name })
        for (wire in WireGeneratedBy.entries - WireGeneratedBy.NONE) assertEquals(wire, wire.toTestkit()?.toWire())
        assertEquals(null, WireGeneratedBy.NONE.toTestkit())
    }

    @Test
    fun `RoutineKind mirrors every wire value but NONE, in order, and maps back`() {
        assertEquals(WireRoutineKind.entries.map { it.name } - "NONE", RoutineKind.entries.map { it.name })
        for (wire in WireRoutineKind.entries - WireRoutineKind.NONE) assertEquals(wire, wire.toTestkit()?.toWire())
        assertEquals(null, WireRoutineKind.NONE.toTestkit())
    }

    @Test
    fun `UnreadShape mirrors every wire value but NONE, in order, and maps back`() {
        assertEquals(WireUnreadShape.entries.map { it.name } - "NONE", UnreadShape.entries.map { it.name })
        for (wire in WireUnreadShape.entries - WireUnreadShape.NONE) assertEquals(wire, wire.toTestkit()?.toWire())
        assertEquals(null, WireUnreadShape.NONE.toTestkit())
    }

    @Test
    fun `EndpointDiscoverySource mirrors every wire value in order and maps back`() {
        assertEquals(WireEndpointDiscoverySource.entries.map { it.name }, EndpointDiscoverySource.entries.map { it.name })
        for (wire in WireEndpointDiscoverySource.entries) assertEquals(wire, wire.toTestkit().toWire())
    }

    @Test
    fun `DependencyDiscoverySource mirrors every wire value in order and maps back`() {
        assertEquals(WireDependencyDiscoverySource.entries.map { it.name }, DependencyDiscoverySource.entries.map { it.name })
        for (wire in WireDependencyDiscoverySource.entries) assertEquals(wire, wire.toTestkit().toWire())
    }
}
