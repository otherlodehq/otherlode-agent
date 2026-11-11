package javax.annotation;

import java.lang.annotation.*;

// Retention is CLASS on purpose, unlike the real annotation: a test needs a listed name that the
// class file keeps out of its runtime-visible annotations. A javax.annotation-api jar on the root
// test classpath would hide this class.
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD})
public @interface PostConstruct {}
