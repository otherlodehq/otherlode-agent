// ASM's JSRInlinerAdapter and the tree classes it is built on, in Byte Buddy's own relocated ASM
// package. Byte Buddy bundles ASM core as net.bytebuddy.jar.asm but leaves out the tree API and
// the subroutine inliner, and the agent's visitors extend Byte Buddy's copy of ClassVisitor and
// MethodVisitor, which the stock asm-commons classes cannot be mixed with. Relocating them into
// the same package lets one class file, JSRInlinerAdapter, plug into the agent's visitor chain
// without a second ASM on the target's classpath. The core classes stay out: Byte Buddy provides
// them.
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

tasks.shadowJar {
    archiveBaseName.set("otherlode-asm-subroutines")
    archiveClassifier.set("")
    // Byte Buddy provides the core classes. Of asm-commons only the inliner is wanted, and of
    // asm-tree only the node classes it is built on, so every other entry is left out by name.
    dependencies {
        exclude(dependency("org.ow2.asm:asm"))
    }
    exclude("org/objectweb/asm/tree/analysis/**", "module-info.class", "META-INF/**")
    exclude(
        "org/objectweb/asm/commons/AdviceAdapter*",
        "org/objectweb/asm/commons/AnalyzerAdapter*",
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
    relocate("org.objectweb.asm", "net.bytebuddy.jar.asm")
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
