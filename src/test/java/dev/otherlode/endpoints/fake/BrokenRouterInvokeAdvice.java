package dev.otherlode.endpoints.fake;

import com.example.framework.BrokenRouter;
import dev.otherlode.bootstrap.OtherlodeEndpoints;
import net.bytebuddy.asm.Advice;

public class BrokenRouterInvokeAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) BrokenRouter.Route route) {
        try {
            throw new NoSuchMethodError("simulated framework mismatch");
        } catch (Throwable t) {
            OtherlodeEndpoints.moduleFailed("broken-router", t);
        }
    }
}
