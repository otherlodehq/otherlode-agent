package dev.soaktest.events

import org.apache.commons.codec.binary.Hex

class LegacyDigest {
    fun hex(bytes: ByteArray): String = Hex.encodeHexString(bytes)
}
