package com.example.target.outsidecaller;

public class StaticDefaultImplsHost implements Runnable {
    @Override
    public void run() {}

    public static class DefaultImpls {
        public static void run(StaticDefaultImplsHost host) {}
    }
}
