package dev.otherlode.instrumentation.endpoints.micronaut3

import net.bytebuddy.ByteBuddy
import net.bytebuddy.description.modifier.Visibility
import net.bytebuddy.description.type.TypeDefinition
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.implementation.StubMethod
import net.bytebuddy.pool.TypePool
import java.io.File
import java.net.URLClassLoader
import java.util.stream.Stream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val DEFAULT_ROUTER = "io.micronaut.web.router.DefaultRouter"
private const val NETTY_HANDLER = "io.micronaut.http.server.netty.RoutingInBoundHandler"
private const val SERVLET_HANDLER = "io.micronaut.servlet.http.ServletHttpHandler"
private const val LAMBDA_HANDLER = "io.micronaut.function.aws.proxy.MicronautLambdaContainerHandler"

/**
 * Pins which classes [Micronaut3Module] accepts: the Micronaut 3 shapes, and nothing of a Micronaut 4
 * application, whose router lists `UriRouteInfo` rather than `UriRoute` and whose Netty and servlet
 * handlers share their names with Micronaut 3's. Each type is described without loading it.
 */
class Micronaut3ModuleMatcherTest {
    private val module = Micronaut3Module()

    @Test
    fun `the classes of the Micronaut release under test are accepted`() {
        val pool = TypePool.Default.of(ClassFileLocator.ForClassLoader.of(javaClass.classLoader))

        val matcher = module.typeMatcher(javaClass.classLoader)
        assertTrue(matcher.matches(pool.describe(DEFAULT_ROUTER).resolve()))
        assertTrue(matcher.matches(pool.describe(NETTY_HANDLER).resolve()))
    }

    @Test
    fun `the classes of the Micronaut 3 floor are accepted`() {
        val (pool, loader) = classesOf("otherlode.micronaut3Floor.jars")
        val matcher = module.typeMatcher(loader)

        listOf(DEFAULT_ROUTER, NETTY_HANDLER, SERVLET_HANDLER, LAMBDA_HANDLER).forEach { name ->
            val type = pool.describe(name)
            assertTrue(type.isResolved, "$name is on the matcher test's class path")
            assertTrue(matcher.matches(type.resolve()), name)
        }
    }

    @Test
    fun `the classes of Micronaut 4 floor are not accepted`() {
        assertNoneAccepted("otherlode.micronaut4Floor.jars")
    }

    @Test
    fun `the classes of the newest tested Micronaut 4 are not accepted`() {
        assertNoneAccepted("otherlode.micronaut4Newest.jars")
    }

    @Test
    fun `a router is accepted only when it lists UriRoute`() {
        val uriRouteInfo: TypeDescription = stub("io.micronaut.web.router.UriRouteInfo")
        val uriRoute: TypeDescription = stub("io.micronaut.web.router.UriRoute")

        assertTrue(module.typeMatcher().matches(router(streamOf(uriRoute))))
        assertFalse(module.typeMatcher().matches(router(streamOf(uriRouteInfo))))
        assertFalse(module.typeMatcher().matches(router(TypeDescription.ForLoadedType.of(Stream::class.java))))
        assertFalse(module.typeMatcher().matches(routerWithoutUriRoutes()))
    }

    @Test
    fun `a handler is accepted by name only on a loader whose router lists UriRoute`() {
        listOf(NETTY_HANDLER, SERVLET_HANDLER, LAMBDA_HANDLER).forEach { name ->
            assertTrue(module.typeMatcher(javaClass.classLoader).matches(handler(name)), name)
            assertFalse(module.typeMatcher(null).matches(handler(name)), name)
            assertFalse(module.typeMatcher().matches(handler(name)), name)
        }
    }

    private fun classesOf(property: String): Pair<TypePool, ClassLoader> {
        val jars = System.getProperty(property).split(File.pathSeparator).map { File(it) }
        assertTrue(jars.isNotEmpty() && jars.all { it.isFile }, "$property: $jars")
        val pool =
            TypePool.Default.of(
                ClassFileLocator.Compound(jars.map { ClassFileLocator.ForJarFile.of(it) } + ClassFileLocator.ForClassLoader.ofBootLoader()),
            )
        return pool to URLClassLoader(jars.map { it.toURI().toURL() }.toTypedArray(), null)
    }

    private fun assertNoneAccepted(property: String) {
        val (pool, loader) = classesOf(property)
        val matcher = module.typeMatcher(loader)

        listOf(DEFAULT_ROUTER, NETTY_HANDLER, SERVLET_HANDLER).forEach { name ->
            val type = pool.describe(name)
            assertTrue(type.isResolved, "$name is on the matcher test's class path")
            assertFalse(matcher.matches(type.resolve()), name)
        }
    }

    private fun handler(name: String): TypeDescription =
        ByteBuddy()
            .subclass(Any::class.java)
            .name(name)
            .make()
            .typeDescription

    @Test
    fun `a type of another name is not accepted`() {
        val other =
            ByteBuddy()
                .subclass(Any::class.java)
                .name("io.micronaut.web.router.Router")
                .make()
                .typeDescription

        assertFalse(module.typeMatcher().matches(other))
    }

    private fun stub(name: String): TypeDescription =
        ByteBuddy()
            .makeInterface()
            .name(name)
            .make()
            .typeDescription

    private fun streamOf(argument: TypeDescription): TypeDefinition =
        TypeDescription.Generic.Builder
            .parameterizedType(TypeDescription.ForLoadedType.of(Stream::class.java), argument)
            .build()

    private fun router(uriRoutes: TypeDefinition): TypeDescription =
        ByteBuddy()
            .subclass(Any::class.java)
            .name(DEFAULT_ROUTER)
            .defineMethod("uriRoutes", uriRoutes, Visibility.PUBLIC)
            .intercept(StubMethod.INSTANCE)
            .make()
            .typeDescription

    private fun routerWithoutUriRoutes(): TypeDescription =
        ByteBuddy()
            .subclass(Any::class.java)
            .name(DEFAULT_ROUTER)
            .make()
            .typeDescription
}
