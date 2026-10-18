package markers;

import grails.boot.GrailsApp;
import grails.boot.config.GrailsAutoConfiguration;
import grails.util.GrailsNameUtils;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.grails.boot.context.web.GrailsAppServletInitializer;
import org.springframework.boot.builder.SpringApplicationBuilder;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Set;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

public final class GrailsMain {
    @RestController
    public static class HelloController {
        @GetMapping("/")
        public String hello() {
            return "ok";
        }
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableAutoConfiguration(
            excludeName = {
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
                "org.springframework.boot.autoconfigure.reactor.ReactorAutoConfiguration",
                "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration"
            })
    public static class Application extends GrailsAutoConfiguration {
        @Bean
        public HelloController helloController() {
            return new HelloController();
        }
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(
            excludeName = {
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
                "org.springframework.boot.autoconfigure.reactor.ReactorAutoConfiguration",
                "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration"
            })
    public static class Batch extends GrailsAutoConfiguration {}

    public static class WarInitializer extends GrailsAppServletInitializer {
        @Override
        protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
            return builder.sources(Application.class);
        }
    }

    private static void servedAsWar() throws Exception {
        int port = Support.freePort();
        File base = Files.createTempDirectory("grails-war-markers").toFile();
        Tomcat tomcat = new Tomcat();
        tomcat.setBaseDir(base.getAbsolutePath());
        tomcat.setHostname("127.0.0.1");
        tomcat.setPort(port);
        tomcat.getConnector();
        Context context = tomcat.addContext("", base.getAbsolutePath());
        context.addServletContainerInitializer((classes, servletContext) -> new WarInitializer().onStartup(servletContext), null);
        tomcat.start();
        try {
            Support.answered(Support.get(port));
        } finally {
            tomcat.stop();
            tomcat.destroy();
            Support.deleteTree(base);
        }
    }

    private static final String[] FACTORIES = {
        "org.springframework.boot.web.servlet.server.ServletWebServerFactory",
        "org.springframework.boot.web.server.servlet.ServletWebServerFactory"
    };
    private static final String[] SERVLET_CONTEXTS = {"jakarta.servlet.ServletContext", "javax.servlet.ServletContext"};

    private static Class<?> present(String... names) throws ClassNotFoundException {
        for (String name : names) {
            if (ClassUtils.isPresent(name, null)) {
                return ClassUtils.forName(name, null);
            }
        }
        throw new ClassNotFoundException(String.join(", ", names));
    }

    private static Object inert(Class<?> type) {
        return Proxy.newProxyInstance(
                GrailsMain.class.getClassLoader(),
                new Class<?>[] {type},
                (proxy, method, args) -> {
                    Class<?> returns = method.getReturnType();
                    if (returns == boolean.class) {
                        return false;
                    }
                    if (returns.isPrimitive() && returns != void.class) {
                        return Array.get(Array.newInstance(returns, 1), 0);
                    }
                    if (returns == Enumeration.class) {
                        return Collections.emptyEnumeration();
                    }
                    if (returns == Set.class) {
                        return Collections.emptySet();
                    }
                    if (returns == String.class) {
                        return method.getName().equals("getContextPath") ? "" : null;
                    }
                    return returns.isInterface() && returns.getPackageName().contains("servlet") ? inert(returns) : null;
                });
    }

    private static Object server() throws ClassNotFoundException {
        Class<?> type = ClassUtils.forName("org.springframework.boot.web.server.WebServer", null);
        return Proxy.newProxyInstance(GrailsMain.class.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            if (method.getName().equals("shutDownGracefully")) {
                Method complete = Arrays.stream(method.getParameterTypes()[0].getMethods())
                        .filter(m -> m.getName().equals("shutdownComplete"))
                        .findFirst()
                        .orElseThrow();
                complete.invoke(args[0], Enum.valueOf(complete.getParameterTypes()[0].asSubclass(Enum.class), "IMMEDIATE"));
                return null;
            }
            Class<?> returns = method.getReturnType();
            if (returns == boolean.class) {
                return false;
            }
            if (returns.isPrimitive() && returns != void.class) {
                return Array.get(Array.newInstance(returns, 1), 0);
            }
            return null;
        });
    }

    private static void withoutServer(ConfigurableApplicationContext context) throws Exception {
        Class<?> factory = present(FACTORIES);
        Object servletContext = inert(present(SERVLET_CONTEXTS));
        Object server = server();
        Object stub =
                Proxy.newProxyInstance(
                        GrailsMain.class.getClassLoader(),
                        new Class<?>[] {factory},
                        (proxy, method, args) -> {
                            for (Object initializer : (Object[]) args[0]) {
                                Method onStartup = initializer.getClass().getMethod("onStartup", present(SERVLET_CONTEXTS));
                                onStartup.setAccessible(true);
                                onStartup.invoke(initializer, servletContext);
                            }
                            return server;
                        });
        ((GenericApplicationContext) context).registerBean("inertServerFactory", (Class<Object>) factory, () -> stub);
    }

    private static ConfigurableApplicationContext run(WebApplicationType type, boolean idle, String... args) {
        GrailsApp app = new GrailsApp(type == WebApplicationType.NONE ? Batch.class : Application.class);
        app.setWebApplicationType(type);
        if (type == WebApplicationType.NONE) {
            app.addInitializers(context -> ((GenericApplicationContext) context)
                    .registerBean("noInterceptor", HandlerInterceptor.class, () -> new HandlerInterceptor() {}));
        }
        if (idle) {
            app.addInitializers(context -> {
                try {
                    withoutServer(context);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        }
        app.setBannerMode(org.springframework.boot.Banner.Mode.OFF);
        return app.run(args);
    }

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "baseline" -> System.out.println("natural name: " + GrailsNameUtils.getNaturalName("fooBar"));
            case "war" -> servedAsWar();
            case "batch" -> {
                run(WebApplicationType.NONE, false).close();
                System.out.println("batch");
            }
            case "configured" -> {
                run(WebApplicationType.SERVLET, true).close();
                System.out.println("configured");
            }
            case "serving" -> {
                int port = Support.freePort();
                ConfigurableApplicationContext context = run(WebApplicationType.SERVLET, false, "--server.port=" + port, "--server.address=127.0.0.1");
                Support.answered(Support.get(port));
                context.close();
            }
            default -> throw new IllegalArgumentException(args[0]);
        }
        Support.finish();
    }
}
