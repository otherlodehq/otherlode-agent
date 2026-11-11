package dev.otherlode.endpoints.fake;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import net.bytebuddy.asm.Advice;

public class FakeRouterPublishAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.This Object router) {
        try {
            OtherlodeEndpoints.declare("fake-router", router);
        } catch (Throwable t) {
            OtherlodeEndpoints.moduleFailed("fake-router", t);
        }
    }
}
