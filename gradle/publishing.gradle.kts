// Apply after declaring the extra properties `otherlode.artifactId`, `otherlode.pomName` and
// `otherlode.pomDescription`, and a `javadocJar` task.
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.Jar
import org.gradle.plugins.signing.SigningExtension
import java.util.concurrent.Callable

val artifactIdValue = project.extra["otherlode.artifactId"] as String
val pomNameValue = project.extra["otherlode.pomName"] as String
val pomDescriptionValue = project.extra["otherlode.pomDescription"] as String
val repositoryUrl = "https://github.com/otherlodehq/otherlode-agent"

val sourcesJar =
    tasks.register<Jar>("sourcesJar") {
        archiveBaseName.set(artifactIdValue)
        archiveClassifier.set("sources")
        from("src/main/kotlin")
        from("src/main/java")
    }

listOf(sourcesJar, tasks.named<Jar>("javadocJar")).forEach { jar ->
    jar.configure {
        from(rootProject.layout.projectDirectory.file("LICENSE")) { into("META-INF") }
        from(layout.projectDirectory.file("NOTICE")) { into("META-INF") }
    }
}

configure<PublishingExtension> {
    publications {
        register<MavenPublication>("otherlode") {
            from(components["shadow"])
            artifact(sourcesJar)
            artifact(tasks.named<Jar>("javadocJar"))
            artifactId = artifactIdValue
            pom {
                name.set(pomNameValue)
                description.set(pomDescriptionValue)
                url.set(repositoryUrl)
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("otherlode")
                        name.set("Luke Killick")
                        email.set("releases@otherlode.dev")
                        organization.set("Otherlode")
                        organizationUrl.set("https://otherlode.dev")
                    }
                }
                scm {
                    url.set(repositoryUrl)
                    connection.set("scm:git:https://github.com/otherlodehq/otherlode-agent.git")
                    developerConnection.set("scm:git:ssh://git@github.com/otherlodehq/otherlode-agent.git")
                }
            }
        }
    }
}

// The key is the signing subkey alone, exported with `gpg --export-secret-subkeys <id>!`. Gradle
// takes exactly the eight hex digits of the id's low 32 bits and fails late and unclearly on anything
// else. An empty variable counts as unset, which is what a workflow passes for a missing secret.
val releaseSubkeyId = "BC8D3F42"

configure<SigningExtension> {
    val key = providers.environmentVariable("SIGNING_KEY").filter { it.isNotBlank() }
    setRequired(
        Callable {
            !project.version.toString().endsWith("-SNAPSHOT") &&
                // build.gradle.kts guards the same task path against a SNAPSHOT.
                gradle.taskGraph.hasTask(":nmcpPublishAggregationToCentralPortal")
        },
    )
    if (key.isPresent) {
        val given = providers.environmentVariable("SIGNING_KEY_ID").filter { it.isNotBlank() }.getOrElse(releaseSubkeyId)
        val hex = given.filterNot { it.isWhitespace() }.removePrefix("0x").removePrefix("0X")
        check(hex.length in setOf(8, 16, 40) && hex.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }) {
            "SIGNING_KEY_ID \"$given\" is not a key id or fingerprint; give the signing subkey's, " +
                "whose last eight hex digits are $releaseSubkeyId for the release key"
        }
        val password =
            providers.environmentVariable("SIGNING_PASSWORD").filter { it.isNotBlank() }.orNull
                ?: error("SIGNING_KEY is set but SIGNING_PASSWORD is not")
        useInMemoryPgpKeys(hex.takeLast(8), key.get(), password)
    }
    sign(the<PublishingExtension>().publications)
}

// Shadow's variant sets no target JVM attribute.
configurations.named("shadowRuntimeElements") {
    attributes { attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 17) }
}
