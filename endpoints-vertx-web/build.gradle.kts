plugins {
    kotlin("jvm") version "2.2.21"
    `jvm-test-suite`
}

group = "dev.otherlode"

repositories {
    mavenCentral()
}

val kotlinVersion = "2.2.21"
val byteBuddyVersion = "1.18.12"
val slf4jVersion = "2.0.16"

val vertx4Version = providers.gradleProperty("otherlode.framework.vertx4").getOrElse("4.5.35")
val vertx5Version = providers.gradleProperty("otherlode.framework.vertx5").getOrElse("5.2.1")

dependencies {
    implementation(project(":endpoints-api"))

    compileOnly(project(":bootstrap"))
    compileOnly("net.bytebuddy:byte-buddy:$byteBuddyVersion")

    // Compiled against the oldest supported release, per ADR 0017. Never `implementation`: Vert.x
    // belongs to the target application and must not be shaded into the agent jar.
    compileOnly("io.vertx:vertx-web:4.5.0")

    testImplementation(project(":"))
    testImplementation(kotlin("test"))
    testImplementation("net.bytebuddy:byte-buddy-agent:$byteBuddyVersion")
    testImplementation("net.bytebuddy:byte-buddy:$byteBuddyVersion")
    testImplementation("io.vertx:vertx-web:$vertx4Version")
    testImplementation("org.slf4j:slf4j-nop:$slf4jVersion")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Djdk.attach.allowAttachSelf=true")
}

// `test` runs the newest 4.x. One more suite runs the tests that start a server against each other
// tested release; the ones that read the module's internals belong to `test` alone.
testing {
    suites {
        for ((suiteName, version) in listOf(
            "vertx450Test" to "4.5.0",
            "vertx5Test" to vertx5Version,
            "vertx50Test" to "5.0.12",
        )) {
            register<JvmTestSuite>(suiteName) {
                useJUnitJupiter()
                sources {
                    kotlin.srcDir("src/test/kotlin")
                    kotlin.exclude("**/PathTemplatesTest.kt", "**/WeakIdentityMapTest.kt")
                }
                dependencies {
                    implementation(project())
                    implementation(project(":"))
                    implementation(project(":endpoints-api"))
                    implementation("org.jetbrains.kotlin:kotlin-test-junit5:$kotlinVersion")
                    implementation("net.bytebuddy:byte-buddy-agent:$byteBuddyVersion")
                    implementation("net.bytebuddy:byte-buddy:$byteBuddyVersion")
                    implementation("io.vertx:vertx-web:$version")
                    implementation("org.slf4j:slf4j-nop:$slf4jVersion")
                }
                targets {
                    all {
                        testTask.configure {
                            jvmArgs("-Djdk.attach.allowAttachSelf=true")
                        }
                    }
                }
            }
        }
    }
}

tasks.check {
    dependsOn(
        testing.suites.named("vertx450Test"),
        testing.suites.named("vertx5Test"),
        testing.suites.named("vertx50Test"),
    )
}
