package dev.otherlode

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.ByteBuffer

/**
 * What the JVM running the tests can define. A class compiled for a newer JVM cannot be defined on an
 * older one, though ASM still reads its bytes, so a test that only analyses bytes keeps running and
 * a test that defines the class skips for that case only.
 */
object ClassFileSupport {
    private const val CLASS_VERSION_OFFSET = 44
    private const val MAJOR_VERSION_OFFSET = 6

    /** The newest class-file major version the running JVM defines. */
    val newestDefinableVersion: Int get() = Runtime.version().feature() + CLASS_VERSION_OFFSET

    /** True when the running JVM defines classes of this class-file major [version]. */
    fun canDefine(version: Int): Boolean = version <= newestDefinableVersion

    /** True when the running JVM defines the class named [className], read from the test class path. */
    fun canDefine(className: String): Boolean {
        val resource = "${className.replace('.', '/')}.class"
        val bytes = checkNotNull(ClassLoader.getSystemResourceAsStream(resource)) { "no class file for $className" }.use { it.readBytes() }
        return canDefine(ByteBuffer.wrap(bytes).getShort(MAJOR_VERSION_OFFSET).toInt())
    }

    /** Skips the calling test unless the running JVM defines every class in [classNames]. */
    fun assumeCanDefine(vararg classNames: String) {
        for (name in classNames) {
            assumeTrue(canDefine(name)) { "$name needs a newer JVM than ${Runtime.version().feature()}" }
        }
    }
}
