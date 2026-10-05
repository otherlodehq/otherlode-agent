// ASM's JSRInlinerAdapter and AnalyzerAdapter and the tree classes the inliner is built on, in Byte
// Buddy's own relocated ASM package. Byte Buddy bundles ASM core as net.bytebuddy.jar.asm but leaves
// out the tree API, the subroutine inliner and the frame analyser, and the agent's visitors extend
// Byte Buddy's copy of ClassVisitor and MethodVisitor, which the stock asm-commons classes cannot be
// mixed with. Relocating them into the same package lets the inliner and the analyser plug into the
// agent's visitor chain without a second ASM on the target's classpath. The core classes stay out:
// Byte Buddy provides them.
plugins {
    java
    id("com.gradleup.shadow")
}

group = "dev.otherlode"

repositories {
    mavenCentral()
}

// The ASM that Byte Buddy bundles (byte-buddy-parent's version.asm): these classes join its copy of
// the package, so they must come from the same release. Move it with every Byte Buddy upgrade.
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

// Byte Buddy provides the core classes. Of asm-commons only the inliner and the analyser are wanted, and of
// asm-tree only the node classes the inliner is built on, so every other entry is left out by name.
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
