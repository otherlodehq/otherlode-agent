package com.example.target.callbacks;

import java.lang.annotation.*;

/** Carries CycleX, which carries this one back. It reaches a listed annotation only through CycleX. */
@Retention(RetentionPolicy.RUNTIME)
@CycleX
public @interface CycleY {}
