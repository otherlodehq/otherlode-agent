package com.example.target.named;

import com.example.outside.named.ClassCall;
import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@ClassCall
public @interface ComposedOfClass {}
