package dev.otherlode.instrumentation

import dev.otherlode.config.AgentConfig
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.registry.EndpointRegistry
import dev.otherlode.registry.ProbeRegistry
import javassist.util.proxy.ProxyFactory
import net.bytebuddy.ByteBuddy
import net.bytebuddy.NamingStrategy
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy
import net.bytebuddy.utility.RandomString
import java.io.File
import java.lang.instrument.ClassFileTransformer
import java.lang.instrument.Instrumentation
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.security.ProtectionDomain
import java.util.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeGeneratedClassTest {
    private companion object {
        const val PACKAGE = "com.example.target"
        const val BASE = "$PACKAGE.SampleTarget"
        const val PACKAGE_PRIVATE_INTERFACE = "$PACKAGE.PackagePrivateGreeter"
    }

    private val instrumentation: Instrumentation = ByteBuddyAgent.install()
    private var otherlode: OtherlodeInstrumentation? = null
    private var installedTransformer: ResettableClassFileTransformer? = null
    private var watcher: ClassFileTransformer? = null

    @AfterTest
    fun tearDown() {
        installedTransformer?.let { otherlode?.uninstall(instrumentation, it) }
        watcher?.let { instrumentation.removeTransformer(it) }
    }

    // A random tail reads as a word about once in 500 names, and TypeMatchPolicy keeps those.
    private fun seeded(prefix: String) =
        NamingStrategy.SuffixingRandom(
            prefix,
            NamingStrategy.Suffixing.BaseNameResolver.ForUnnamedType.INSTANCE,
            "net.bytebuddy.renamed",
            RandomString(RandomString.DEFAULT_LENGTH, Random(7L)),
        )

    @Test
    fun `classes ByteBuddy, Mockito, javassist and the JDK generate are offered to the agent and left alone`() {
        val offered = mutableSetOf<String>()
        val spy =
            object : ClassFileTransformer {
                override fun transform(
                    loader: ClassLoader?,
                    className: String?,
                    classBeingRedefined: Class<*>?,
                    protectionDomain: ProtectionDomain?,
                    classfileBuffer: ByteArray,
                ): ByteArray? {
                    if (className != null && className.startsWith("com/example/target/")) {
                        synchronized(offered) { offered += className.replace('/', '.') }
                    }
                    return null
                }
            }
        instrumentation.addTransformer(spy, false)
        watcher = spy

        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=$PACKAGE")
        val instrumented = OtherlodeInstrumentation(config, registry)
        otherlode = instrumented
        installedTransformer = instrumented.install(instrumentation)

        val loader = FixtureClassLoader(arrayOf(File("build/classes/java/test").toURI().toURL()), javaClass.classLoader)
        val base = Class.forName(BASE, true, loader)

        val byteBuddy =
            ByteBuddy()
                .with(seeded("ByteBuddy"))
                .subclass(base)
                .make()
                .load(loader, ClassLoadingStrategy.Default.WRAPPER)
                .loaded
        val mockitoShaped =
            ByteBuddy()
                .with(seeded("MockitoMock"))
                .subclass(base)
                .make()
                .load(loader, ClassLoadingStrategy.Default.WRAPPER)
                .loaded
        val javassist = ProxyFactory().apply { superclass = base }.createClass()
        val greeter = Class.forName(PACKAGE_PRIVATE_INTERFACE, true, loader)
        val jdkProxy = Proxy.newProxyInstance(loader, arrayOf(greeter), InvocationHandler { _, _, _ -> "hi" }).javaClass
        val named =
            ByteBuddy()
                .subclass(base)
                .name("$PACKAGE.SampleTargetSubclass")
                .make()
                .load(loader, ClassLoadingStrategy.Default.WRAPPER)
                .loaded

        val generated = listOf(byteBuddy, mockitoShaped, javassist, jdkProxy).map { it.name }
        assertTrue("\$ByteBuddy\$" in byteBuddy.name, byteBuddy.name)
        assertTrue("\$MockitoMock\$" in mockitoShaped.name, mockitoShaped.name)
        assertTrue("_\$\$_jvst" in javassist.name, javassist.name)
        assertTrue(jdkProxy.name.startsWith("$PACKAGE.\$Proxy"), jdkProxy.name)
        for (name in generated + named.name) {
            assertTrue(name in synchronized(offered) { offered.toSet() }, "$name never reached a transformer, so this proved nothing")
        }

        LoadedClassSweep(instrumentation, registry, EndpointRegistry(), config).run(runForwardPass = true)
        val manifest = registry.manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
        for (name in generated) {
            assertTrue(manifest.probes.none { it.className == name }, "$name was woven")
            assertTrue(manifest.skippedClasses.none { it.className == name }, "$name was reported skipped")
            assertTrue(manifest.unreportedClasses.none { it.className == name }, "$name was reported by the sweep")
        }
        assertTrue(manifest.probes.any { it.className == named.name }, "a ByteBuddy class under an ordinary name is woven")
    }

    @Test
    fun `a class ByteBuddy names under the fixed naming mode is left alone only while that mode is on`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=$PACKAGE")
        val instrumented = OtherlodeInstrumentation(config, registry)
        otherlode = instrumented
        installedTransformer = instrumented.install(instrumentation)

        fun defineFixedNamed(): Class<*> {
            val loader = FixtureClassLoader(arrayOf(File("build/classes/java/test").toURI().toURL()), javaClass.classLoader)
            val base = Class.forName(BASE, true, loader)
            return ByteBuddy()
                .with(
                    NamingStrategy.Suffixing(
                        "ByteBuddy",
                        NamingStrategy.Suffixing.BaseNameResolver.ForUnnamedType.INSTANCE,
                        "net.bytebuddy.renamed",
                    ),
                ).subclass(base)
                .make()
                .load(loader, ClassLoadingStrategy.Default.WRAPPER)
                .loaded
        }

        val woven = defineFixedNamed()
        val previous = TypeMatchPolicy.byteBuddyNaming
        TypeMatchPolicy.byteBuddyNaming = "fixed"
        val leftAlone =
            try {
                defineFixedNamed()
            } finally {
                TypeMatchPolicy.byteBuddyNaming = previous
            }

        assertTrue(woven.name == "$BASE\$ByteBuddy" && leftAlone.name == woven.name, "${woven.name} ${leftAlone.name}")
        val manifest = registry.manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
        val probes = manifest.probes.filter { it.className == woven.name }
        assertEquals(1, probes.map { it.classId }.distinct().size, "exactly one of the two copies is woven: $probes")
    }
}
