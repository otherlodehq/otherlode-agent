package com.example.target;

public class GeneratedForwarderCaller {

    public static Price callsOverloadConstructor() {
        return new Price(1);
    }

    public static String callsOverloadMethod(Price price) {
        return price.format(1);
    }

    public static String callsTopLevelOverload() {
        return GeneratedTargetKt.formatPrice(1L);
    }

    @SuppressWarnings("deprecation")
    public static int callsDefaultImpls(GeneratedInterface target) {
        return GeneratedInterface.DefaultImpls.withBody(target);
    }

    public static String callsMultifileFacade() {
        return MultifileText.multifileGreeting("x");
    }
}
