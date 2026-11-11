package dev.otherlode.instrumentation.endpoints.jdkhttpserver

import dev.otherlode.export.EndpointLocation
import java.lang.invoke.MethodType
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal fun assertJoinsDeclaredMethod(
    endpoint: EndpointLocation,
    owner: Class<*>,
    methodPrefix: String,
    descriptor: String,
) {
    val route = endpoint.routeTemplate
    assertEquals(owner.name, endpoint.handlerClass, "$route: handler class")
    val method = assertNotNull(endpoint.handlerMethod, "$route: handler method")
    assertTrue(method.startsWith(methodPrefix), "$route: handler method $method should start with $methodPrefix")
    assertEquals(descriptor, endpoint.handlerDescriptor, "$route: handler descriptor")
    val declared =
        owner.declaredMethods.any {
            it.name == method && MethodType.methodType(it.returnType, it.parameterTypes).toMethodDescriptorString() == descriptor
        }
    assertTrue(declared, "$route: ${owner.name} declares no method $method$descriptor")
}

internal fun assertNoJoin(
    endpoint: EndpointLocation,
    why: String,
) {
    assertNull(endpoint.handlerClass, "${endpoint.routeTemplate}: $why")
    assertNull(endpoint.handlerMethod, "${endpoint.routeTemplate}: $why")
    assertNull(endpoint.handlerDescriptor, "${endpoint.routeTemplate}: $why")
}
