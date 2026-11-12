import org.gradle.process.CommandLineArgumentProvider

plugins {
    kotlin("jvm") version "2.2.21"
}

group = "dev.otherlode"

repositories {
    mavenCentral()
}

val byteBuddyVersion = "1.18.12"
val ktorVersion = "2.3.13"

// The framework canary sets this to the newest 2.x. compileOnly stays on ktorVersion.
val ktorTestVersion = providers.gradleProperty("otherlode.framework.ktor2").getOrElse("2.3.13")

dependencies {
    implementation(project(":endpoints-api"))

    // At runtime OtherlodeEndpoints comes from the target JVM's bootstrap classloader.
    compileOnly(project(":bootstrap"))

    compileOnly("net.bytebuddy:byte-buddy:$byteBuddyVersion")

    // Never `implementation`, since Ktor must not be shaded into the agent jar.
    compileOnly("io.ktor:ktor-server-core-jvm:$ktorVersion")

    testImplementation(project(":"))
    testImplementation(kotlin("test"))

    testImplementation("net.bytebuddy:byte-buddy:$byteBuddyVersion")

    testImplementation("io.ktor:ktor-server-core-jvm:$ktorTestVersion")
    testImplementation("io.ktor:ktor-server-cio-jvm:$ktorTestVersion")
}

kotlin {
    jvmToolchain(21)
}

// Ktor2TestAgent must be a real -javaagent, not a self-attach: see the comment on it.
val ktor2TestAgentJar =
    tasks.register<Jar>("ktor2TestAgentJar") {
        archiveBaseName.set("ktor2-test-agent")
        destinationDirectory.set(layout.buildDirectory.dir("test-agent"))
        from(sourceSets.test.get().output)
        manifest {
            attributes["Premain-Class"] = "dev.otherlode.instrumentation.endpoints.ktor2.Ktor2TestAgent"
            attributes["Can-Retransform-Classes"] = "true"
        }
    }

tasks.test {
    dependsOn(ktor2TestAgentJar)
    useJUnitPlatform()
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf("-javaagent:${ktor2TestAgentJar.get().archiveFile.get().asFile.absolutePath}")
        },
    )
}
