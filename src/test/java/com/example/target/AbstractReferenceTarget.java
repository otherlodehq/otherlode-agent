package com.example.target;

import com.example.library.Lib;

public abstract class AbstractReferenceTarget {
    public abstract Lib.AbstractReturn abs(Lib.AbstractParam p);

    public int concrete() {
        return 1;
    }
}
