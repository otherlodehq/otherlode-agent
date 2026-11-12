package dev.otherlode.instrumentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ProbeLayoutHashTest {
    @Test
    fun `same signatures produce the same hash`() {
        val signatures = listOf("bar()V", "foo(I)Z")

        assertEquals(ProbeLayoutHash.of(signatures), ProbeLayoutHash.of(signatures))
    }

    @Test
    fun `reordering the same signatures changes the hash`() {
        val a = listOf("bar()V", "foo(I)Z", "baz()I")
        val b = listOf("foo(I)Z", "baz()I", "bar()V")

        assertNotEquals(ProbeLayoutHash.of(a), ProbeLayoutHash.of(b))
    }

    @Test
    fun `adding a method changes the hash`() {
        val before = listOf("bar()V")
        val after = listOf("bar()V", "foo(I)Z")

        assertNotEquals(ProbeLayoutHash.of(before), ProbeLayoutHash.of(after))
    }

    @Test
    fun `a signature list hashes to the same value on every JVM`() {
        // The expected value is FNV-1a over each signature's UTF-8 bytes, each followed by 0xff, computed outside the JVM.
        assertEquals(-134230149565136201L, ProbeLayoutHash.of(listOf("bar()V", "foo(I)Z")))
    }

    @Test
    fun `a type initializer slot changes the hash`() {
        val without = listOf("bar()V", "foo(I)Z")
        val with = without + "<clinit>()V#typeinit"

        assertNotEquals(ProbeLayoutHash.of(without), ProbeLayoutHash.of(with))
    }

    @Test
    fun `a changed optional parameter set changes the hash`() {
        val before = listOf("f\$default(IILjava/lang/Object;)I#optional2")
        val after = listOf("f\$default(IILjava/lang/Object;)I#optional6")

        assertNotEquals(ProbeLayoutHash.of(before), ProbeLayoutHash.of(after))
    }

    @Test
    fun `the type initializer entry names its outcome slots`() {
        val base = listOf("bar()V")

        assertEquals("<clinit>()V#typeinit+outcomes", ProbeLayoutHash.TYPE_INITIALIZER_ENTRY)
        assertNotEquals(
            ProbeLayoutHash.of(base + "<clinit>()V#typeinit"),
            ProbeLayoutHash.of(base + ProbeLayoutHash.TYPE_INITIALIZER_ENTRY),
        )
    }
}
