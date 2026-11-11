package dev.otherlode.instrumentation.endpoints.micronautcommon

import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.pool.TypePool
import java.lang.reflect.Method

/** The class, method name and descriptor of a handler as its class file declares it. */
class Handler(
    val className: String,
    val methodName: String,
    val descriptor: String,
)

/**
 * Joins a method-based Micronaut route to the method it runs, reading class files and loading
 * nothing. It takes the reflective `getTargetMethod` of a method-based route and `MethodReference`'s
 * `getDeclaringType`, `getMethodName` and `getArgumentTypes`.
 */
class HandleJoin(
    private val getTargetMethod: Method,
    private val getDeclaringType: Method,
    private val getMethodName: Method,
    private val getArgumentTypes: Method,
) {
    private val pools = HashMap<ClassLoader?, TypePool>()

    /** The class Micronaut names and the method name of [route]'s target, or null for a route that is not method-based. */
    fun target(route: Any): Pair<String, String>? {
        if (!getTargetMethod.declaringClass.isInstance(route)) return null
        val target = getTargetMethod.invoke(route) ?: return null
        return (getDeclaringType.invoke(target) as Class<*>).name to (getMethodName.invoke(target) as String)
    }

    /**
     * The method [route] runs, as the class file that declares it has it, or null when the route is
     * not method-based or the method is not found. For an AOP bean Micronaut names the generated
     * `$Definition$Intercepted` subclass, so the method is searched among the bean's superclasses
     * and interfaces by name and arguments.
     */
    fun of(route: Any): Handler? {
        if (!getTargetMethod.declaringClass.isInstance(route)) return null
        val target = getTargetMethod.invoke(route) ?: return null
        val beanType = getDeclaringType.invoke(target) as Class<*>
        val methodName = getMethodName.invoke(target) as String
        val arguments = (getArgumentTypes.invoke(target) as Array<*>).map { (it as Class<*>).name }
        return try {
            val pool =
                pools.getOrPut(
                    beanType.classLoader,
                ) { TypePool.Default.of(ClassFileLocator.ForClassLoader.of(beanType.classLoader)) }
            declaringMethod(pool, pool.describe(beanType.name).resolve(), methodName, arguments)?.let {
                Handler(it.declaringType.asErasure().name, methodName, it.descriptor)
            }
        } catch (e: RuntimeException) {
            null
        }
    }
}

internal fun declaringMethod(
    pool: TypePool,
    beanType: TypeDescription,
    methodName: String,
    arguments: List<String>,
): MethodDescription? {
    val classes = generateSequence(beanType) { it.superClass?.asErasure() }.filterNot { it.name.endsWith(INTERCEPTED_SUFFIX) }.toList()
    val interfaces = LinkedHashMap<String, TypeDescription>()
    val pending = ArrayDeque(classes.flatMap { type -> type.interfaces.map { it.asErasure() } })
    while (pending.isNotEmpty()) {
        val type = pending.removeFirst()
        if (interfaces.putIfAbsent(type.name, type) == null) type.interfaces.forEach { pending.addLast(it.asErasure()) }
    }

    fun candidates(types: Collection<TypeDescription>): List<MethodDescription> =
        types.flatMap { type ->
            type.declaredMethods.filter {
                it.internalName == methodName &&
                    it.parameters.size == arguments.size &&
                    !(it.isBridge && it.isSynthetic) &&
                    !it.isAbstract &&
                    !it.isStatic &&
                    !it.isPrivate
            }
        }
    val inClasses = candidates(classes)
    val inInterfaces = candidates(interfaces.values)

    fun pick(accepts: (MethodDescription) -> Boolean): MethodDescription? {
        inClasses.firstOrNull(accepts)?.let { return it }
        val accepted = inInterfaces.filter(accepts)
        return accepted
            .filter { method ->
                accepted.none { other ->
                    other.declaringType != method.declaringType &&
                        other.declaringType.asErasure().isAssignableTo(method.declaringType.asErasure())
                }
            }.singleOrNull()
    }

    val argumentTypes by lazy { arguments.map { pool.describe(it).resolve() } }
    return pick { method -> method.parameters.map { it.type.asErasure().name } == arguments }
        ?: pick { method ->
            method.parameters.zip(argumentTypes).all { (parameter, argument) ->
                argument.isAssignableTo(parameter.type.asErasure())
            }
        }
}

private const val INTERCEPTED_SUFFIX = "\$Intercepted"
