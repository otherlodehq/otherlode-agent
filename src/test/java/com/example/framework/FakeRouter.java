package com.example.framework;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class FakeRouter {
    public static final class Route {
        public final String verb;
        public final String path;
        public final Runnable handler;

        public Route(String verb, String path, Runnable handler) {
            this.verb = verb;
            this.path = path;
            this.handler = handler;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Route)) return false;
            Route route = (Route) other;
            return Objects.equals(verb, route.verb) && Objects.equals(path, route.path);
        }

        @Override
        public int hashCode() {
            return Objects.hash(verb, path);
        }
    }

    private final List<Route> routes = new ArrayList<>();

    public void addRoute(String verb, String path, Runnable handler) {
        routes.add(new Route(verb, path, handler));
    }

    public void addQuietly(String verb, String path, Runnable handler) {
        routes.add(new Route(verb, path, handler));
    }

    public List<Route> routes() {
        return routes;
    }

    public void publishRoutes() {
    }

    public boolean dispatch(String verb, String path) {
        for (Route route : routes) {
            if (route.verb.equals(verb) && route.path.equals(path)) {
                invoke(route);
                return true;
            }
        }
        return false;
    }

    private void invoke(Route route) {
        route.handler.run();
    }
}
