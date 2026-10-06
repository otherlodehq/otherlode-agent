package com.example.target.outsidecaller;

import java.util.AbstractList;

/** A raw {@code AbstractList}, so its methods keep the erased descriptors the JDK declares. */
@SuppressWarnings("rawtypes")
public class RawList extends AbstractList {
    @Override
    public Object get(int index) {
        return null;
    }

    @Override
    public int size() {
        return 0;
    }
}
