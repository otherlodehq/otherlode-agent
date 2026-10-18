package dev.otherlode.endpoints.servlet;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import java.util.List;
import javax.servlet.DispatcherType;
import javax.servlet.ServletContext;
import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletMapping;
import javax.servlet.http.HttpServletRequest;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto the entry of every non-abstract {@code service(ServletRequest, ServletResponse)} a
 * servlet class declares, {@code HttpServlet}'s own included. The container calls that method on
 * the servlet, so it is the servlet's entry, and a filter that answers first never reaches it.
 *
 * <p>A servlet whose public {@code service} calls {@code super.service} enters several hooked
 * frames for one request. The dispatch key carries the name of the class the frame is hooked on,
 * and the module binds a servlet's keys only for the most derived class that declares the method,
 * so exactly one frame finds an entry. The protected {@code service(HttpServletRequest,
 * HttpServletResponse)} is not hooked: {@code HttpServlet}'s public {@code service} always reaches
 * it first.
 *
 * <p>A request counts only for a {@code REQUEST} dispatch. The first lookup uses the request's
 * verb, the second the servlet's {@code *} verb. A miss then hands the servlet context to the
 * module, which declares it once, and looks again.
 *
 * <p>This advice is written against the {@code javax} servlet types and bound to the {@code
 * jakarta} ones, by prefix, where the woven method takes them. On a servlet API older than 4.0
 * {@code getHttpServletMapping} does not link, and the failure disables the module with the kind
 * {@code LINKAGE_ERROR}.
 *
 * <p>This method is deliberately one flat body with no private helper method of its own; see the
 * Spring MVC module's {@code RegisterHandlerMethodAdvice} Javadoc for why.
 */
public class ServletServiceAdvice {
    private static final String MODULE = "servlet";

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Origin("#t") String hooked, @Advice.Argument(0) ServletRequest request) {
        try {
            if (!(request instanceof HttpServletRequest)) return;
            HttpServletRequest http = (HttpServletRequest) request;
            if (http.getDispatcherType() != DispatcherType.REQUEST) return;
            ServletContext context = http.getServletContext();
            HttpServletMapping mapping = http.getHttpServletMapping();
            String name = mapping.getServletName();
            String pattern = mapping.getPattern();
            String method = http.getMethod();
            if (context == null || name == null || pattern == null || method == null) return;

            Object entry = null;
            for (int attempt = 0; entry == null && attempt < 2; attempt++) {
                if (attempt == 1) OtherlodeEndpoints.declare(MODULE, context);
                entry = OtherlodeEndpoints.lookup(MODULE, List.of(context, name, pattern, method, hooked));
                if (entry == null) {
                    entry = OtherlodeEndpoints.lookup(MODULE, List.of(context, name, pattern, "*", hooked));
                }
            }
            if (entry == null) return;
            if (mapping.getMappingMatch() == null) {
                OtherlodeEndpoints.moduleDisabled(
                        MODULE, OtherlodeEndpoints.KIND_ADVICE_FAILED, "the container matched a declared servlet with no mapping match type");
                return;
            }
            OtherlodeEndpoints.hit(entry);
        } catch (Throwable t) {
            OtherlodeEndpoints.moduleFailed(MODULE, t);
        }
    }
}
