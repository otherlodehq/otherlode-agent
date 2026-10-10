package com.example.outside.named;

import java.lang.annotation.Repeatable;

/** A repeatable marker with no retention policy, so it and its container have class retention. */
@Repeatable(ClassRepeatedList.class)
public @interface ClassRepeated {}
