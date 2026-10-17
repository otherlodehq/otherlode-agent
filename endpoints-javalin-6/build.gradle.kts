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

val javalin6Version = providers.gradleProperty("otherlode.framework.javalin6").getOrElse("6.7.0")

dependencies {
    implementation(project(":endpoints-api"))

    compileOnly(project(":bootstrap"))
    compileOnly("net.bytebuddy:byte-buddy:$byteBuddyVersion")

    // Compiled against the oldest supported release, per ADR 0017. Never `implementation`: Javalin
    // belongs to the target application and must not be shaded into the agent jar.
    compileOnly("io.javalin:javalin:6.1.0")

    testImplementation(project(":"))
    testImplementation(project(":endpoints-javalin-7"))
    testImplementation(kotlin("test"))
    testImplementation("net.bytebuddy:byte-buddy-agent:$byteBuddyVersion")
    testImplementation("net.bytebuddy:byte-buddy:$byteBuddyVersion")
    testImplementation("io.javalin:javalin:$javalin6Version")
    testImplementation("org.slf4j:slf4j-nop:$slf4jVersion")
}

kotlin {
    jvmToolchain(21)
}

sourceSets.test {
    kotlin.srcDir("src/harness/kotlin")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Djdk.attach.allowAttachSelf=true")
}

testing {
    suites {
        register<JvmTestSuite>("javalin61Test") {
            useJUnitJupiter()
            sources {
                kotlin.srcDir("src/test/kotlin")
                kotlin.srcDir("src/harness/kotlin")
            }
            dependencies {
                implementation(project())
                implementation(project(":"))
                implementation(project(":endpoints-api"))
                implementation(project(":endpoints-javalin-7"))
                implementation("org.jetbrains.kotlin:kotlin-test-junit5:$kotlinVersion")
                implementation("net.bytebuddy:byte-buddy-agent:$byteBuddyVersion")
                implementation("net.bytebuddy:byte-buddy:$byteBuddyVersion")
                implementation("io.javalin:javalin:6.1.0")
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

        register<JvmTestSuite>("javalin60Test") {
            useJUnitJupiter()
            sources {
                kotlin.srcDir("src/harness/kotlin")
            }
            dependencies {
                implementation(project())
                implementation(project(":"))
                implementation(project(":endpoints-api"))
                implementation(project(":endpoints-javalin-7"))
                implementation("org.jetbrains.kotlin:kotlin-test-junit5:$kotlinVersion")
                implementation("net.bytebuddy:byte-buddy-agent:$byteBuddyVersion")
                implementation("net.bytebuddy:byte-buddy:$byteBuddyVersion")
                implementation("io.javalin:javalin:6.0.1")
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

tasks.check {
    dependsOn(testing.suites.named("javalin61Test"))
    dependsOn(testing.suites.named("javalin60Test"))
}
