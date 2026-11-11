package dev.otherlode.endpoints.lambdafactory;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import java.lang.invoke.MethodHandleInfo;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto the exit of {@code java.lang.invoke.InnerClassLambdaMetafactory.spinInnerClass()},
 * which returns the hidden class the JDK spins for one lambda or method reference. It hands that
 * class, the functional interface it implements, and the method it calls to {@link
 * OtherlodeEndpoints#recordLambdaClass}.
 *
 * <p>This advice is inlined into {@code java.base}, so it may only call the bootstrap seam. It
 * must not contain a lambda or a method reference: either would call the method it is woven into.
 * The field names must match {@code LambdaFactoryShape.JDK}, which checks them on the running JDK
 * before the hook installs.
 */
public class SpinInnerClassAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
            @Advice.Return Class<?> lambdaClass,
            @Advice.FieldValue("interfaceClass") Class<?> interfaceClass,
            @Advice.FieldValue("implInfo") MethodHandleInfo implInfo) {
        OtherlodeEndpoints.recordLambdaClass(lambdaClass, interfaceClass, implInfo);
    }
}
