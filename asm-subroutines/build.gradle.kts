plugins {
    java
    id("com.gradleup.shadow")
}

group = "dev.otherlode"

repositories {
    mavenCentral()
}

// Must be the ASM that Byte Buddy bundles (byte-buddy-parent's version.asm). Move it with every
// Byte Buddy upgrade.
val asmVersion = "9.10.1"

dependencies {
    implementation("org.ow2.asm:asm:$asmVersion")
    implementation("org.ow2.asm:asm-tree:$asmVersion")
    implementation("org.ow2.asm:asm-commons:$asmVersion")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.jar {
    enabled = false
}

val excludedEntries =
    listOf(
        "org/objectweb/asm/tree/analysis/**",
        "module-info.class",
        "META-INF/**",
        "org/objectweb/asm/commons/AdviceAdapter*",
        "org/objectweb/asm/commons/*Remapper*",
        "org/objectweb/asm/commons/CodeSizeEvaluator*",
        "org/objectweb/asm/commons/GeneratorAdapter*",
        "org/objectweb/asm/commons/InstructionAdapter*",
        "org/objectweb/asm/commons/LocalVariablesSorter*",
        "org/objectweb/asm/commons/Method*",
        "org/objectweb/asm/commons/Module*",
        "org/objectweb/asm/commons/SerialVersionUIDAdder*",
        "org/objectweb/asm/commons/SimpleRemapper*",
        "org/objectweb/asm/commons/StaticInitMerger*",
        "org/objectweb/asm/commons/TableSwitchGenerator*",
        "org/objectweb/asm/commons/TryCatchBlockSorter*",
    )

tasks.shadowJar {
    archiveBaseName.set("otherlode-asm-subroutines")
    archiveClassifier.set("")
    dependencies {
        exclude(dependency("org.ow2.asm:asm"))
    }
    // The shadow task does not track exclude patterns as inputs, so editing them would leave a stale jar.
    inputs.property("excludedEntries", excludedEntries)
    exclude(excludedEntries)
    relocate("org.objectweb.asm", "net.bytebuddy.jar.asm")
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
