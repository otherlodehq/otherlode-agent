package dev.otherlode.endpoints.lambdafactory;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import java.lang.invoke.MethodHandleInfo;
import net.bytebuddy.asm.Advice;

/**
 * Runs on exit from {@code java.lang.invoke.InnerClassLambdaMetafactory.spinInnerClass()},
 * which returns the hidden class the JDK spins for one lambda or method reference. It hands that
 * class, the functional interface it implements, and the method it calls to {@link
 * OtherlodeEndpoints#recordLambdaClass}.
 *
 * <p>The field names must match {@code LambdaFactoryShape.JDK}.
 */
public class SpinInnerClassAdvice {
    // Inlined into java.base: it may call only the bootstrap seam, and a lambda or method
    // reference here would call the method it is woven into.
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
            @Advice.Return Class<?> lambdaClass,
            @Advice.FieldValue("interfaceClass") Class<?> interfaceClass,
            @Advice.FieldValue("implInfo") MethodHandleInfo implInfo) {
        OtherlodeEndpoints.recordLambdaClass(lambdaClass, interfaceClass, implInfo);
    }
}
