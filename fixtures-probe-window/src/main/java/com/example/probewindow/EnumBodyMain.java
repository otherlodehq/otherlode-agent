package com.example.probewindow;

/** Initializes an enum constant's body class by name before its enum has been initialized. */
public class EnumBodyMain {
    public static void main(String[] args) throws Exception {
        Class.forName("com.example.probewindow.Op$1");
        System.out.println("apply " + Op.PLUS.apply(2, 3));
    }
}
