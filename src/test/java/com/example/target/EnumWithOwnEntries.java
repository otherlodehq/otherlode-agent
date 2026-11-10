package com.example.target;

import java.util.List;

/** A Java enum that declares its own instance `getEntries` returning a string. */
enum EnumWithStringEntries {
    ONE;

    public String getEntries() {
        return name();
    }
}

/** A Java enum that declares its own instance `getEntries` returning a list. */
enum EnumWithListEntries {
    ONE;

    public List<String> getEntries() {
        return List.of(name());
    }
}

/** A Java enum that declares a static `getEntries` that takes a parameter. */
enum EnumWithParameterEntries {
    ONE;

    public static String getEntries(int index) {
        return "" + index;
    }
}

/** A Java enum with an instance `getEntries` that returns an `EnumEntries`. */
enum EnumWithInstanceEnumEntries {
    ONE;

    public kotlin.enums.EnumEntries<EnumWithInstanceEnumEntries> getEntries() {
        return null;
    }
}

/** A Java enum with a static `getEntries` that takes a parameter and returns an `EnumEntries`. */
enum EnumWithParameterEnumEntries {
    ONE;

    public static kotlin.enums.EnumEntries<EnumWithParameterEnumEntries> getEntries(int index) {
        return null;
    }
}
