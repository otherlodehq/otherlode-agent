package com.example.target;

import java.util.List;

enum EnumWithStringEntries {
    ONE;

    public String getEntries() {
        return name();
    }
}

enum EnumWithListEntries {
    ONE;

    public List<String> getEntries() {
        return List.of(name());
    }
}

enum EnumWithParameterEntries {
    ONE;

    public static String getEntries(int index) {
        return "" + index;
    }
}

enum EnumWithInstanceEnumEntries {
    ONE;

    public kotlin.enums.EnumEntries<EnumWithInstanceEnumEntries> getEntries() {
        return null;
    }
}

enum EnumWithParameterEnumEntries {
    ONE;

    public static kotlin.enums.EnumEntries<EnumWithParameterEnumEntries> getEntries(int index) {
        return null;
    }
}
