package com.example.target.keypairs

import dev.otherlode.instrumentation.branch.BranchKeys
import dev.otherlode.instrumentation.branch.BranchSite
import dev.otherlode.instrumentation.branch.BranchSiteAnalyzer
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.commons.ClassRemapper
import net.bytebuddy.jar.asm.commons.SimpleRemapper
import java.io.File

fun fixtureLookup(internalName: String): ByteArray? =
    listOf("kotlin", "java")
        .map { File("build/classes/$it/test/$internalName.class") }
        .firstOrNull { it.isFile }
        ?.readBytes()

// A method ordinal counts sites within that method, not the class-wide BranchSite.siteIndex.
class KeyedBuild(
    val name: String,
    val sites: List<BranchSite>,
    private val keys: Map<Pair<Int, Int>, String>,
    private val siteKeys: Map<Int, String> = emptyMap(),
) {
    private fun siteAt(
        methodName: String,
        methodOrdinal: Int,
    ): BranchSite = sites.filter { it.methodName == methodName }[methodOrdinal]

    fun keyOf(
        methodName: String,
        methodOrdinal: Int = 0,
        outcome: Int = 0,
    ): String? = keys[siteAt(methodName, methodOrdinal).siteIndex to outcome]

    fun keysByLabel(methodName: String): Map<String, String?> {
        val site = sites.single { it.methodName == methodName && it.dropReason == null && it.caseLabels != null }
        val labels = site.caseLabels!!.map { it.text } + "default"
        return labels.withIndex().associate { (offset, label) -> label to keys[site.siteIndex to offset] }
    }

    fun siteKeyOf(methodName: String): String? {
        val site = sites.single { it.methodName == methodName && it.dropReason == null && it.caseLabels != null }
        return siteKeys[site.siteIndex]
    }

    // Must match the running total KeptBranchSite.of keeps while it numbers outcomes.
    fun branchIndexOf(
        methodName: String,
        methodOrdinal: Int = 0,
        outcome: Int = 0,
    ): Int {
        val site = siteAt(methodName, methodOrdinal)
        return sites.filter { it.siteIndex < site.siteIndex }.sumOf { it.outcomeCount } + outcome
    }
}

fun kotlinFixtureBytes(simpleName: String): ByteArray =
    File("build/classes/kotlin/test/com/example/target/keypairs/$simpleName.class").readBytes()

fun javaFixtureBytes(simpleName: String): ByteArray =
    File("build/classes/java/test/com/example/target/keypairs/$simpleName.class").readBytes()

fun renamedTo(
    bytes: ByteArray,
    fromInternal: String,
    toInternal: String,
): ByteArray {
    val writer = ClassWriter(0)
    ClassReader(bytes).accept(ClassRemapper(writer, SimpleRemapper(fromInternal, toInternal)), 0)
    return writer.toByteArray()
}

fun keyedBuildsOf(
    v1SimpleName: String,
    v2SimpleName: String,
    commonSimpleName: String = "Common",
    bytesOf: (String) -> ByteArray = ::kotlinFixtureBytes,
    includePackages: List<String> = listOf("com.example"),
    lookup: (internalName: String) -> ByteArray? = { null },
    methodFilter: (name: String, descriptor: String) -> Boolean = { _, _ -> true },
): Pair<KeyedBuild, KeyedBuild> {
    val v1Internal = "com/example/target/keypairs/$v1SimpleName"
    val v2Internal = "com/example/target/keypairs/$v2SimpleName"
    val commonInternal = "com/example/target/keypairs/$commonSimpleName"
    val commonDotted = commonInternal.replace('/', '.')

    val v1Bytes = renamedTo(bytesOf(v1SimpleName), v1Internal, commonInternal)
    val v2Bytes = renamedTo(bytesOf(v2SimpleName), v2Internal, commonInternal)

    val v1Analysis = BranchSiteAnalyzer.analyze(v1Bytes, lookup, includePackages = includePackages, methodFilter = methodFilter)
    val v2Analysis = BranchSiteAnalyzer.analyze(v2Bytes, lookup, includePackages = includePackages, methodFilter = methodFilter)

    val v1Keys = BranchKeys.compute(v1Analysis.sites, commonDotted)
    val v2Keys = BranchKeys.compute(v2Analysis.sites, commonDotted)

    return KeyedBuild(v1SimpleName, v1Analysis.sites, v1Keys, BranchKeys.computeSiteKeys(v1Analysis.sites, commonDotted)) to
        KeyedBuild(v2SimpleName, v2Analysis.sites, v2Keys, BranchKeys.computeSiteKeys(v2Analysis.sites, commonDotted))
}
