package com.example.target;

import com.example.library.Lib;

public class WidgetUser {
    public Lib.FieldType field;

    public Object make() {
        return new Lib.Widget();
    }

    public Object missing() {
        return new Lib.Helper();
    }
}
