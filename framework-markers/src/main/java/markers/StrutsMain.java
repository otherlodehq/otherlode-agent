package markers;

import java.io.File;
import java.nio.file.Files;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;

public final class StrutsMain {
    private static final String[] FILTERS = {
        "org/apache/struts2/dispatcher/filter/StrutsPrepareAndExecuteFilter",
        "org/apache/struts2/dispatcher/ng/filter/StrutsPrepareAndExecuteFilter"
    };
    private static final String[] SUPPORT = {"org/apache/struts2/ActionSupport", "com/opensymphony/xwork2/ActionSupport"};
    private static final String[] DTDS = {"struts-7.0.dtd", "struts-6.0.dtd", "struts-2.5.dtd", "struts-2.3.dtd"};

    private static String present(String... candidates) {
        ClassLoader loader = StrutsMain.class.getClassLoader();
        for (String candidate : candidates) {
            String resource = candidate.contains("/") ? candidate + ".class" : candidate;
            if (loader.getResource(resource) != null) {
                return candidate;
            }
        }
        throw new IllegalStateException("none of " + String.join(", ", candidates));
    }

    private static Tomcat tomcat(int port) throws Exception {
        File base = Files.createTempDirectory("struts-markers").toFile();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> Support.deleteTree(base)));
        Tomcat tomcat = new Tomcat();
        tomcat.setBaseDir(base.getAbsolutePath());
        tomcat.setHostname("127.0.0.1");
        tomcat.getConnector().setPort(port);
        tomcat.getConnector().setProperty("address", "127.0.0.1");
        Context context = tomcat.addContext("", base.getAbsolutePath());
        Tomcat.initWebappDefaults(context);
        FilterDef definition = new FilterDef();
        definition.setFilterName("struts2");
        definition.setFilterClass(present(FILTERS).replace('/', '.'));
        String dtd = present(DTDS);
        definition.addInitParameter("config", "struts-default.xml,markers/" + dtd.replace(".dtd", ".xml"));
        context.addFilterDef(definition);
        FilterMap mapping = new FilterMap();
        mapping.setFilterName("struts2");
        mapping.addURLPattern("/*");
        context.addFilterMap(mapping);
        return tomcat;
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable e) {
            e.printStackTrace();
            System.exit(1);
        }
        Support.finish();
    }

    private static void run(String[] args) throws Exception {
        switch (args[0]) {
            case "baseline" -> {
                String name = present(SUPPORT).replace('/', '.');
                Object support = Class.forName(name).getDeclaredConstructor().newInstance();
                System.out.println("action support: " + support.getClass().getSimpleName());
            }
            case "configured" -> {
                tomcat(Support.freePort());
                Object filter = Class.forName(present(FILTERS).replace('/', '.')).getDeclaredConstructor().newInstance();
                System.out.println("configured: " + filter.getClass().getName());
            }
            case "serving" -> {
                int port = Support.freePort();
                Tomcat tomcat = tomcat(port);
                tomcat.start();
                Support.answered(Support.get(port, "/hello.action"));
                tomcat.stop();
                tomcat.destroy();
            }
            default -> throw new IllegalArgumentException(args[0]);
        }
    }
}
