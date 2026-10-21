package dev.otherlode.instrumentation.endpoints.micronaut

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
private const val ROUTE_EXECUTOR = "io.micronaut.http.server.RouteExecutor"

/**
 * Pins which classes [MicronautModule] accepts: the 4.0 to 5.x shapes, and nothing of a Micronaut 3
 * application, whose router lists `UriRoute` rather than `UriRouteInfo` and whose `RouteExecutor` has
 * no `findRouteMatch`. Each type is described without loading it.
 */
class MicronautModuleMatcherTest {
    private val module = MicronautModule()

    @Test
    fun `the classes of the Micronaut release under test are accepted`() {
        val pool = TypePool.Default.of(ClassFileLocator.ForClassLoader.of(javaClass.classLoader))

        val matcher = module.typeMatcher(javaClass.classLoader)
        assertTrue(matcher.matches(pool.describe(DEFAULT_ROUTER).resolve()))
        assertTrue(matcher.matches(pool.describe(ROUTE_EXECUTOR).resolve()))
    }

    @Test
    fun `the classes of Micronaut 3 are not accepted`() {
        val jars = System.getProperty("otherlode.micronaut3.jars").split(File.pathSeparator).map { File(it) }
        assertTrue(jars.isNotEmpty() && jars.all { it.isFile }, "$jars")
        val pool =
            TypePool.Default.of(
                ClassFileLocator.Compound(jars.map { ClassFileLocator.ForJarFile.of(it) } + ClassFileLocator.ForClassLoader.ofBootLoader()),
            )

        val micronaut3 = URLClassLoader(jars.map { it.toURI().toURL() }.toTypedArray(), null)
        val matcher = module.typeMatcher(micronaut3)

        val router = pool.describe(DEFAULT_ROUTER)
        assertTrue(router.isResolved, "the Micronaut 3 router is on the matcher test's class path")
        assertFalse(matcher.matches(router.resolve()))
        val executor = pool.describe(ROUTE_EXECUTOR)
        assertTrue(executor.isResolved, "the Micronaut 3 server is on the matcher test's class path")
        assertFalse(matcher.matches(executor.resolve()))
    }

    @Test
    fun `a router is accepted only when it lists UriRouteInfo`() {
        val uriRouteInfo: TypeDescription = stub("io.micronaut.web.router.UriRouteInfo")
        val uriRoute: TypeDescription = stub("io.micronaut.web.router.UriRoute")

        assertTrue(module.typeMatcher().matches(router(streamOf(uriRouteInfo))))
        assertFalse(module.typeMatcher().matches(router(streamOf(uriRoute))))
        assertFalse(module.typeMatcher().matches(router(TypeDescription.ForLoadedType.of(Stream::class.java))))
        assertFalse(module.typeMatcher().matches(routerWithoutUriRoutes()))
    }

    @Test
    fun `a route executor is accepted by name only on a loader whose router lists UriRouteInfo`() {
        val request: TypeDescription = stub("io.micronaut.http.HttpRequest")
        val other: TypeDescription = stub("io.micronaut.web.router.RouteMatch")

        assertTrue(module.typeMatcher(javaClass.classLoader).matches(executor(request, other)))
        assertTrue(module.typeMatcher(javaClass.classLoader).matches(executorWithoutFindRouteMatch()))
        assertFalse(module.typeMatcher(null).matches(executorWithoutFindRouteMatch()))
        assertFalse(module.typeMatcher().matches(executorWithoutFindRouteMatch()))
    }

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

    private fun executor(
        request: TypeDescription,
        returned: TypeDescription,
    ): TypeDescription =
        ByteBuddy()
            .subclass(Any::class.java)
            .name(ROUTE_EXECUTOR)
            .defineMethod("findRouteMatch", returned, Visibility.PACKAGE_PRIVATE)
            .withParameters(request)
            .intercept(StubMethod.INSTANCE)
            .make()
            .typeDescription

    private fun executorWithoutFindRouteMatch(): TypeDescription =
        ByteBuddy()
            .subclass(Any::class.java)
            .name(ROUTE_EXECUTOR)
            .make()
            .typeDescription
}
