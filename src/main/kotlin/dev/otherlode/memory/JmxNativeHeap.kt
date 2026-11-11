package dev.otherlode.memory

import com.sun.management.HotSpotDiagnosticMXBean
import java.lang.management.ManagementFactory
import javax.management.InstanceNotFoundException
import javax.management.MBeanServer
import javax.management.ObjectName
import javax.management.ReflectionException

// The startup thread loads this class and only the `otherlode-trim` thread links it. It must not
// extend a `java.management` type, or loading it needs that module on the startup thread.
internal object JmxNativeHeap : NativeHeap {
    private const val OPTION = "TrimNativeHeapInterval"
    private const val OPERATION = "systemTrimNativeHeap"
    private const val DIAGNOSTIC_COMMAND = "com.sun.management:type=DiagnosticCommand"

    private lateinit var server: MBeanServer

    override fun open() {
        server = ManagementFactory.getPlatformMBeanServer()
    }

    override fun jvmTrimInterval(): String? =
        try {
            ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean::class.java).getVMOption(OPTION).value
        } catch (_: IllegalArgumentException) {
            null
        }

    override fun trim(): String =
        try {
            server
                .invoke(ObjectName(DIAGNOSTIC_COMMAND), OPERATION, arrayOf<Any>(arrayOf<String>()), arrayOf("[Ljava.lang.String;"))
                .toString()
        } catch (_: ReflectionException) {
            throw NoTrimCommand()
        } catch (_: InstanceNotFoundException) {
            throw NoTrimCommand()
        }
}
