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
 * <p>A {@code REQUEST}, {@code FORWARD} or {@code INCLUDE} dispatch counts at the servlet it
 * reaches; {@code ASYNC} and {@code ERROR} count nothing. A forward's mapping is the request's own.
 * An include leaves the request's mapping as the original one, so the included servlet's mapping is
 * read from the request attribute {@code RequestDispatcher.INCLUDE_MAPPING}, whose name the advice
 * builds for the namespace in use because a string constant is not rebound. When that mapping names
 * a servlet other than the hooked one, as Undertow's does (it holds the including servlet's mapping)
 * and as it does for a servlet called directly during an include, the hooked servlet's own
 * registration answers: the mapping equal to the include's servlet path when its path info is null,
 * or to that path plus {@code /*} when it is not, else nothing counts. Every counted dispatch hands
 * the context to the module. A request whose mapping has no match
 * type disables the module before anything is declared: the match type is null for the servlet API's
 * default {@code getHttpServletMapping}, which a container that does not implement Servlet 4.0
 * answers with (LINKAGE_ERROR), and for a request object that hides the real mapping
 * (ADVICE_FAILED). Otherwise the context is handed to the module, which declares it once, and the
 * first lookup uses the request's verb, the second the servlet's {@code *} verb.
 *
 * <p>The advice is woven into servlet classes at their own class file version, which can be 48. It
 * therefore holds no string concatenation with {@code +}, which compiles to {@code invokedynamic},
 * and no class literal, which version 48 cannot load.
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
            DispatcherType dispatch = http.getDispatcherType();
            String namespace = DispatcherType.REQUEST.getDeclaringClass().getName().startsWith("javax") ? "javax" : "jakarta";
            HttpServletMapping mapping;
            if (dispatch == DispatcherType.REQUEST || dispatch == DispatcherType.FORWARD) {
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
