package dev.otherlode.endpoints.servlet;

import dev.otherlode.bootstrap.OtherlodeEndpoints;
import java.util.Arrays;
import javax.servlet.DispatcherType;
import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletRegistration;
import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletMapping;
import javax.servlet.http.HttpServletRequest;
import net.bytebuddy.asm.Advice;

/**
 * Woven onto the entry of every non-abstract {@code service(ServletRequest, ServletResponse)} a
 * servlet class declares, {@code HttpServlet}'s own included. A filter that answers first never
 * reaches it. The protected {@code service(HttpServletRequest, HttpServletResponse)} is not hooked.
 *
 * <p>The dispatch key carries the name of the class the frame is hooked on, and the module binds a
 * servlet's keys only for the most derived class that declares the method, so one frame finds an
 * entry when a {@code service} calls {@code super.service}. A frame counts nothing when the servlet
 * it runs on has a name of its own in its {@code ServletConfig} that differs from the mapping's,
 * except for an include, which falls back to that servlet's own registration.
 *
 * <p>A request whose mapping has no match type disables the module: with {@code LINKAGE_ERROR}
 * when the container answers with the servlet API's default {@code getHttpServletMapping}, else
 * with {@code ADVICE_FAILED}. On a servlet API older than 4.0, {@code getHttpServletMapping} does
 * not link and the failure disables the module with {@code LINKAGE_ERROR}.
 *
 * <p>The advice is woven into servlet classes at their own class file version, which can be 48, so
 * it uses no string concatenation with {@code +} and no class literal. The Servlet 3.1 {@code
 * HttpServlet} is version 51, so it calls no static interface method such as {@code List.of}. It
 * is written against the {@code javax} servlet types and bound to the {@code jakarta} ones by
 * prefix. A string constant is not rebound, so each request attribute name is built for the
 * namespace in use.
 *
 * <p>The advice body is inlined into the woven method, so {@code onEnter} stays one flat body with
 * no helper methods.
 */
public class ServletServiceAdvice {
    private static final String MODULE = "servlet";

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
            @Advice.Origin("#t") String hooked, @Advice.This Object servlet, @Advice.Argument(0) ServletRequest request) {
        try {
            if (!(request instanceof HttpServletRequest)) return;
            HttpServletRequest http = (HttpServletRequest) request;
            DispatcherType dispatch = http.getDispatcherType();
            String namespace = DispatcherType.REQUEST.getDeclaringClass().getName().startsWith("javax") ? "javax" : "jakarta";
            HttpServletMapping mapping;
            if (dispatch == DispatcherType.REQUEST
                    || dispatch == DispatcherType.FORWARD
                    || dispatch == DispatcherType.ERROR
                    || dispatch == DispatcherType.ASYNC) {
                mapping = http.getHttpServletMapping();
            } else if (dispatch == DispatcherType.INCLUDE) {
                Object included = http.getAttribute(namespace.concat(".servlet.include.mapping"));
                if (!(included instanceof HttpServletMapping)) return;
                mapping = (HttpServletMapping) included;
            } else {
                return;
            }
            ServletContext context = http.getServletContext();
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
            if (own != null && !own.equals(name)) {
                if (dispatch != DispatcherType.INCLUDE) return;
                ServletRegistration registration = context.getServletRegistration(own);
                if (registration == null) return;
                Object servletPath = http.getAttribute(namespace.concat(".servlet.include.servlet_path"));
                Object pathInfo = http.getAttribute(namespace.concat(".servlet.include.path_info"));
                if (!(servletPath instanceof String)) return;
                String path = (String) servletPath;
                boolean exact = pathInfo == null || "".equals(pathInfo);
                String prefix = path.concat("/*");
                String found = null;
                for (String candidate : registration.getMappings()) {
                    if (exact && candidate.equals(path)) found = candidate;
                }
                for (String candidate : registration.getMappings()) {
                    if (found == null && candidate.equals(prefix)) found = candidate;
                }
                if (found == null) return;
                name = own;
                pattern = found;
            }

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
