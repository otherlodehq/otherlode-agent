package com.example.probewindow;

public class InterfaceConstantMain {
    public static void main(String[] args) throws Exception {
        Class<?> anonymous = Class.forName("com.example.probewindow.Filter$1");
        Filter filter = (Filter) anonymous.getDeclaredConstructor().newInstance();
        System.out.println("matches " + filter.matches("java.x"));
    }
}
