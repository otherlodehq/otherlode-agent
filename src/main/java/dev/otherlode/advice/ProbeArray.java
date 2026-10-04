package dev.otherlode.advice;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds an {@link net.bytebuddy.asm.Advice} parameter to the woven class's counts array. The
 * instrumenting code supplies the load at weave time: a dynamic constant for a class of version
 * 55 or later, a call to the class's private accessor below that.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface ProbeArray {
}
