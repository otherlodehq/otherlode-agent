package dev.otherlode.instrumentation.endpoints.micronaut3;

import io.micronaut.context.ExecutionHandleLocator;
import io.micronaut.web.router.DefaultRouteBuilder;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Singleton
public class ProgrammaticRoutes extends DefaultRouteBuilder {
    @Inject
    public ProgrammaticRoutes(ExecutionHandleLocator executionHandleLocator) {
        super(executionHandleLocator);
    }

    @Inject
    void routes(ProgrammaticTarget target, HeadTarget headTarget) {
        GET("/programmatic", target, "handle");
        HEAD("/programmatic-head", headTarget, "both");
    }
}
