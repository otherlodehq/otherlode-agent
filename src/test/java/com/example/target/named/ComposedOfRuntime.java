package com.example.target.named;

import com.example.outside.named.RuntimeCall;
import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@RuntimeCall
public @interface ComposedOfRuntime {}
