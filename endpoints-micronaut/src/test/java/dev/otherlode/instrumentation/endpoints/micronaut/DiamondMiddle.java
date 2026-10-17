package dev.otherlode.instrumentation.endpoints.micronaut;

public interface DiamondMiddle extends DiamondTop {
    @Override
    default String m() {
        return "middle";
    }
}
