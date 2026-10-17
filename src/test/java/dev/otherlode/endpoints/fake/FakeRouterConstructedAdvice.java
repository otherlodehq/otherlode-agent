package dev.otherlode.endpoints.fake;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import net.bytebuddy.asm.Advice;

/** Woven onto the exit of a {@code FakeRouter} constructor, reporting the new router as a registered route. */
public class FakeRouterConstructedAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.This Object router) {
        OtherlodeEndpoints.register("fake-router", router, "GET", "/constructed", null, null, null, null);
    }
}
