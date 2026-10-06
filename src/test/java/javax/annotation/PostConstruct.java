package javax.annotation;

import java.lang.annotation.*;

/**
 * A test stand-in for the framework annotation of the same binary name. Its retention is CLASS on
 * purpose, unlike the real one, so a test can show that a listed name the class file keeps out of
 * its runtime-visible annotations marks nothing. Putting javax.annotation-api on the root test
 * classpath would hide this class and that test's point.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD})
public @interface PostConstruct {}
