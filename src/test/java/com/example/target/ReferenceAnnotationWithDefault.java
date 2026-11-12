package com.example.target;

import com.example.library.Lib;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface ReferenceAnnotationWithDefault {
    Class<?> type() default Lib.DefaultElementValue.class;
}
