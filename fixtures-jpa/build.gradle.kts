// Entity classes enhanced by Ebean, EclipseLink and OpenJPA, for the rules of ADR 0079. Each
// library runs its own offline enhancer over the compiled entities, then the callers are compiled
// against the enhanced classes so they can name the methods the enhancer added. Never on the test
// classpath directly: see the comment in fixtures-scala3/build.gradle.kts for why. The root build
// passes each variant's class directory and runtime classpath to the tests as system properties.
plugins {
    java
}

group = "dev.otherlode"

repositories {
    mavenCentral()
}

val ebeanAgentVersion = "19.7.0"
val ebeanApiVersion = "19.6.0"
val eclipseLinkVersion = "4.0.9"
val openJpaVersion = "4.1.1"
val jakartaPersistenceVersion = "3.1.0"

val toolchains = extensions.getByType<JavaToolchainService>()

val jpaApi by configurations.creating {
    isCanBeConsumed = false
}
val ebeanEnhancer by configurations.creating {
    isCanBeConsumed = false
}
val ebeanRuntime by configurations.creating {
    isCanBeConsumed = false
}
val eclipseLinkRuntime by configurations.creating {
    isCanBeConsumed = false
}
val openJpaRuntime by configurations.creating {
    isCanBeConsumed = false
}

dependencies {
    jpaApi("jakarta.persistence:jakarta.persistence-api:$jakartaPersistenceVersion")
    ebeanEnhancer("io.ebean:ebean-agent:$ebeanAgentVersion")
    ebeanRuntime("io.ebean:ebean-api:$ebeanApiVersion")
    eclipseLinkRuntime("org.eclipse.persistence:eclipselink:$eclipseLinkVersion")
    openJpaRuntime("org.apache.openjpa:openjpa:$openJpaVersion")
}

/** One library's fixtures: its sources, its runtime classpath, and how its enhancer runs over the compiled classes. */
class Variant(
    val name: String,
    val runtime: Configuration,
    val enhancerClasspath: Configuration,
    val mainClass: String,
    val arguments: (raw: File, enhanced: File) -> List<String>,
)

val variants =
    listOf(
        Variant("ebean", ebeanRuntime, ebeanEnhancer, "io.ebean.enhance.ant.MainTransform") { _, enhanced ->
            listOf(enhanced.absolutePath, "com.example.target.jpa.ebean", "debug=0")
        },
        Variant(
            "eclipselink",
            eclipseLinkRuntime,
            eclipseLinkRuntime,
            "org.eclipse.persistence.tools.weaving.jpa.StaticWeave",
        ) { raw, enhanced ->
            listOf(
                "-persistenceinfo",
                layout.projectDirectory
                    .dir("src/eclipselink")
                    .asFile.absolutePath,
                raw.absolutePath,
                enhanced.absolutePath,
            )
        },
        Variant("openjpa", openJpaRuntime, openJpaRuntime, "org.apache.openjpa.enhance.PCEnhancer") { _, enhanced ->
            listOf("-properties", "META-INF/persistence.xml#fixture", "-d", enhanced.absolutePath)
        },
    )

variants.forEach { variant ->
    val name = variant.name
    val raw = layout.buildDirectory.dir("raw/$name")
    val enhanced = layout.buildDirectory.dir("enhanced/$name")
    val callers = layout.buildDirectory.dir("callers/$name")
    val assembled = layout.buildDirectory.dir("classes/$name")
    val compileClasspath = jpaApi + variant.runtime

    val compile =
        tasks.register<JavaCompile>("compile${name.replaceFirstChar(Char::uppercase)}") {
            group = "build"
            description = "Compiles the $name entities."
            source = fileTree("src/$name") { include("**/*.java") }
            classpath = compileClasspath
            destinationDirectory.set(raw)
            javaCompiler.set(toolchains.compilerFor { languageVersion.set(JavaLanguageVersion.of(21)) })
            options.release.set(17)
        }

    val enhance =
        tasks.register<JavaExec>("enhance${name.replaceFirstChar(Char::uppercase)}") {
            group = "build"
            description = "Runs $name's own enhancer over the compiled entities."
            dependsOn(compile)
            inputs.dir(raw).withPropertyName("raw")
            inputs.dir("src/$name").withPropertyName("sources")
            outputs.dir(enhanced).withPropertyName("enhanced")
            javaLauncher.set(toolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
            mainClass.set(variant.mainClass)
            val rawDir = raw.map { it.asFile }
            val enhancedDir = enhanced.map { it.asFile }
            classpath = variant.enhancerClasspath + jpaApi + variant.runtime + files(rawDir, "src/$name")
            argumentProviders.add(CommandLineArgumentProvider { variant.arguments(rawDir.get(), enhancedDir.get()) })
            doFirst {
                enhancedDir.get().deleteRecursively()
                enhancedDir.get().mkdirs()
                if (name == "ebean") rawDir.get().copyRecursively(enhancedDir.get(), overwrite = true)
            }
        }

    val compileCallers =
        tasks.register<JavaCompile>("compile${name.replaceFirstChar(Char::uppercase)}Callers") {
            group = "build"
            description = "Compiles the $name callers against the enhanced entities."
            dependsOn(enhance)
            source = fileTree("callers/$name") { include("**/*.java") }
            classpath = compileClasspath + files(enhanced)
            destinationDirectory.set(callers)
            javaCompiler.set(toolchains.compilerFor { languageVersion.set(JavaLanguageVersion.of(21)) })
            options.release.set(17)
        }

    tasks.register<Sync>("assemble${name.replaceFirstChar(Char::uppercase)}") {
        group = "build"
        description = "Gathers the enhanced $name entities and their callers into one class directory."
        dependsOn(enhance, compileCallers)
        from(enhanced)
        from(raw) { include("com/example/outside/**") }
        from(callers)
        into(assembled)
    }
}

tasks.named("classes") {
    dependsOn(variants.map { "assemble${it.name.replaceFirstChar(Char::uppercase)}" })
}

/** The class directory of variant [name], with its enhanced entities and callers. */
val fixtureClassesDirs = variants.associate { it.name to layout.buildDirectory.dir("classes/${it.name}") }

/** The runtime classpath variant [name] needs to define its entities, which the tests add to the fixture loader. */
val fixtureRuntimeClasspaths = variants.associate { it.name to it.runtime }

extra["fixtureClassesDirs"] = fixtureClassesDirs
extra["fixtureRuntimeClasspaths"] = fixtureRuntimeClasspaths
