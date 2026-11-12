package dev.otherlode.shutdown;

import dev.otherlode.bootstrap.OtherlodeShutdownHooks;
import java.util.Map;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto the entry of {@code java.lang.ApplicationShutdownHooks.runHooks}. It hands the
 * class's own {@code hooks} field, the table of application hooks, to {@link
 * OtherlodeShutdownHooks#offer}.
 */
public class ShutdownHooksAdvice {
    // Inlined into java.base: it may call only the bootstrap seam.
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.FieldValue("hooks") Map<Thread, Thread> hooks) {
        OtherlodeShutdownHooks.offer(hooks);
    }
}
