package dev.otherlode.endpoints.servlet;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import java.util.Arrays;
import javax.servlet.DispatcherType;
import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
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
 * <p>A servlet that calls another servlet's {@code service} directly enters a hooked frame on the
 * other servlet too, with the first servlet's mapping. That frame counts nothing when the servlet it
 * runs on has a name of its own in its {@code ServletConfig}, and so is not the one the container
 * matched. A servlet with no config, or none that names itself, counts.
 *
 * <p>A request counts only for a {@code REQUEST} dispatch. A request whose mapping has no match
 * type disables the module before anything is declared: the match type is null for the servlet API's
 * default {@code getHttpServletMapping}, which a container that does not implement Servlet 4.0
 * answers with (LINKAGE_ERROR), and for a request object that hides the real mapping
 * (ADVICE_FAILED). Otherwise the context is handed to the module, which declares it once, and the
 * first lookup uses the request's verb, the second the servlet's {@code *} verb.
 *
 * <p>This advice is written against the {@code javax} servlet types and bound to the {@code
 * jakarta} ones, by prefix, where the woven method takes them. On a servlet API older than 4.0
 * {@code getHttpServletMapping} does not link, and the failure disables the module with the kind
 * {@code LINKAGE_ERROR}.
 *
 * <p>The Servlet 3.1 API's {@code HttpServlet} is class file version 51, which cannot call a static
 * interface method such as {@code List.of}: the woven class would fail to verify.
 *
 * <p>This method is deliberately one flat body with no private helper method of its own; see the
 * Spring MVC module's {@code RegisterHandlerMethodAdvice} Javadoc for why.
 */
public class ServletServiceAdvice {
    private static final String MODULE = "servlet";

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
            @Advice.Origin("#t") String hooked, @Advice.This Object servlet, @Advice.Argument(0) ServletRequest request) {
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
            if (mapping.getMappingMatch() == null) {
                if (name.isEmpty() && pattern.isEmpty()) {
                    OtherlodeEndpoints.moduleDisabled(
                            MODULE,
                            OtherlodeEndpoints.KIND_LINKAGE_ERROR,
                            "getHttpServletMapping answered with the servlet API's default, so the container does not implement Servlet 4.0");
                } else {
                    OtherlodeEndpoints.moduleDisabled(
                            MODULE, OtherlodeEndpoints.KIND_ADVICE_FAILED, "the request's servlet mapping has no match type");
                }
                return;
            }
            ServletConfig config = ((Servlet) servlet).getServletConfig();
            String own = config == null ? null : config.getServletName();
            if (own != null && !own.equals(name)) return;

            OtherlodeEndpoints.declare(MODULE, context);
            Object entry = OtherlodeEndpoints.lookup(MODULE, Arrays.<Object>asList(context, name, pattern, method, hooked));
            if (entry == null) {
                entry = OtherlodeEndpoints.lookup(MODULE, Arrays.<Object>asList(context, name, pattern, "*", hooked));
            }
            if (entry != null) OtherlodeEndpoints.hit(entry);
        } catch (Throwable t) {
            OtherlodeEndpoints.moduleFailed(MODULE, t);
        }
    }
}
