package markers;

import play.ApplicationLoader;
import play.BuiltInComponentsFromContext;
import play.Environment;
import java.util.Collections;
import java.util.List;
import play.mvc.EssentialFilter;
import play.libs.Json;
import play.mvc.Results;
import play.routing.Router;
import play.routing.RoutingDsl;
import play.server.Server;

public final class PlayMain {
    private static final class Components extends BuiltInComponentsFromContext {
        Components() {
            super(ApplicationLoader.create(Environment.simple()));
        }

        @Override
        public List<EssentialFilter> httpFilters() {
            return Collections.emptyList();
        }

        @Override
        public Router router() {
            return RoutingDsl.fromComponents(this).GET("/").routingTo(request -> Results.ok("ok")).build();
        }
    }

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "baseline" -> System.out.println("json: " + Json.toJson("ok"));
            case "configured" -> {
                new Components().router();
                play.core.server.ServerProvider$.MODULE$.fromConfiguration(
                        PlayMain.class.getClassLoader(), new play.api.Configuration(com.typesafe.config.ConfigFactory.load()));
                System.out.println("built");
            }
            case "serving" -> {
                Server server = new Server.Builder().http(0).build(components -> RoutingDsl.fromComponents(components).GET("/").routingTo(request -> Results.ok("ok")).build());
                Support.answered(Support.get(server.httpPort()));
                server.stop();
            }
            default -> throw new IllegalArgumentException(args[0]);
        }
        Support.finish();
    }
}
