package dev.otherlode.instrumentation.endpoints.micronaut3;

public interface DiamondMiddle extends DiamondTop {
    @Override
    default String m() {
        return "middle";
    }
}
