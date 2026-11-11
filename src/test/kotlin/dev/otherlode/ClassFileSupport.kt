package dev.otherlode

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.ByteBuffer

object ClassFileSupport {
    private const val CLASS_VERSION_OFFSET = 44
    private const val MAJOR_VERSION_OFFSET = 6

    val newestDefinableVersion: Int get() = Runtime.version().feature() + CLASS_VERSION_OFFSET

    fun canDefine(version: Int): Boolean = version <= newestDefinableVersion

    fun canDefine(className: String): Boolean {
        val resource = "${className.replace('.', '/')}.class"
        val bytes = checkNotNull(ClassLoader.getSystemResourceAsStream(resource)) { "no class file for $className" }.use { it.readBytes() }
        return canDefine(ByteBuffer.wrap(bytes).getShort(MAJOR_VERSION_OFFSET).toInt())
    }

    fun assumeCanDefine(vararg classNames: String) {
        for (name in classNames) {
            assumeTrue(canDefine(name)) { "$name needs a newer JVM than ${Runtime.version().feature()}" }
        }
    }
}
