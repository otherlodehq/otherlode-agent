package javax.annotation;

import java.lang.annotation.*;

// Retention must stay CLASS, unlike the real annotation.
// A javax.annotation-api jar on the root test classpath would hide this class.
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD})
public @interface PostConstruct {}
