package com.example.framework;

import java.util.ArrayList;
import java.util.List;

public class BrokenRouter {
    public static final class Route {
        public final String verb;
        public final String path;
        public final Runnable handler;

        public Route(String verb, String path, Runnable handler) {
            this.verb = verb;
            this.path = path;
            this.handler = handler;
        }
    }

    private final List<Route> routes = new ArrayList<>();

    public void addRoute(String verb, String path, Runnable handler) {
        routes.add(new Route(verb, path, handler));
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
