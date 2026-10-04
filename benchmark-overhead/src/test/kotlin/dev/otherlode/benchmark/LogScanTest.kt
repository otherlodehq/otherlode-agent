package dev.otherlode.benchmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LogScanTest {
    @Test
    fun `reads the process running time from the started line`() {
        val log =
            "2026-10-04T10:00:00.000Z  INFO 1 --- [main] o.s.s.p.PetClinicApplication : " +
                "Started PetClinicApplication in 6.329 seconds (process running for 6.944)"
        assertEquals(6.944, LogScan.bootProcessSeconds(log)!!, 1e-9)
    }

    @Test
    fun `no started line gives null`() {
        assertNull(LogScan.bootProcessSeconds("nothing here"))
    }

    @Test
    fun `finds a verify error`() {
        assertTrue(LogScan.hasVerifyError("Caused by: java.lang.VerifyError: Bad type on operand stack"))
        assertFalse(LogScan.hasVerifyError("all fine"))
    }

    @Test
    fun `collects the classes the agent skipped, once each, in order`() {
        val log =
            """
            WARNING: otherlode: instrumentation failed for org.springframework.Foo, class will run uninstrumented
            java.lang.IllegalStateException: boom
            WARNING: otherlode: instrumentation failed for org.springframework.Bar${'$'}Inner, class will run uninstrumented
            WARNING: otherlode: instrumentation failed for org.springframework.Foo, class will run uninstrumented
            WARNING: otherlode: endpoint instrumentation failed for org.springframework.Baz, class will run without endpoint tracking
            """.trimIndent()
        assertEquals(listOf("org.springframework.Foo", "org.springframework.Bar\$Inner"), LogScan.skippedClasses(log))
    }

    @Test
    fun `reads resident set size and its high-water mark`() {
        val status = "Name:\tjava\nVmHWM:\t  120332 kB\nVmRSS:\t  110332 kB\nThreads:\t5\n"
        val p = ProcStatus.parse(status)
        assertEquals(110332L * 1024, p.rssBytes)
        assertEquals(120332L * 1024, p.hwmBytes)
    }

    @Test
    fun `reads class space from the metaspace report`() {
        val report =
            """
            1:

            Total Usage - 39 loaders, 2618 classes (1008 shared):
              Non-Class:  280 chunks,  10924032 bytes capacity,8630272 bytes ( 79%) committed, 8532680 bytes ( 78%) used, 97384 bytes ( <1%) free,   208 bytes ( <1%) waste , deallocated: 0 blocks with 0 bytes
                  Class:  100 chunks,  1266688 bytes capacity,1201152 bytes ( 95%) committed, 1114496 bytes ( 88%) used, 86616 bytes (  7%) free,    40 bytes ( <1%) waste , deallocated: 54 blocks with 13256 bytes
                   Both:  380 chunks,  12190720 bytes capacity,9831424 bytes ( 81%) committed, 9647176 bytes ( 79%) used, 184000 bytes (  2%) free,   248 bytes ( <1%) waste , deallocated: 54 blocks with 13256 bytes
            """.trimIndent()
        val c = ClassSpace.parse(report)!!
        assertEquals(1201152L, c.committedBytes)
        assertEquals(1114496L, c.usedBytes)
    }

    @Test
    fun `reads k6's summary export`() {
        val json =
            """
            {"metrics":{
              "http_reqs":{"count":1000,"rate":55.5},
              "http_req_duration":{"avg":10.5,"med":9,"p(95)":20,"p(99)":30,"max":99},
              "iteration_duration":{"avg":150,"med":140,"p(95)":200,"p(99)":250,"max":300},
              "checks":{"passes":995,"fails":5,"value":0.995}
            }}
            """.trimIndent()
        val k = K6Summary.parse(json)
        assertEquals(55.5, k.metrics["k6ThroughputPerSec"])
        assertEquals(10.5, k.metrics["httpReqAvgMs"])
        assertEquals(20.0, k.metrics["httpReqP95Ms"])
        assertEquals(30.0, k.metrics["httpReqP99Ms"])
        assertEquals(150.0, k.metrics["iterationAvgMs"])
        assertEquals(200.0, k.metrics["iterationP95Ms"])
        assertEquals(0.995, k.checksPassRate, 1e-9)
    }

    @Test
    fun `reads the cgroup's CPU usage`() {
        val stat = "usage_usec 12345678\nuser_usec 10000000\nsystem_usec 2345678\nnr_periods 0\n"
        assertEquals(12_345_678L, CgroupCpu.parse(stat).usageMicros)
    }
}
