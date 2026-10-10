package com.example.target.inherit;

/** Overrides every annotated supertype method without repeating its annotation. */
public class InheritImpl extends InheritBase implements InheritApi, GenericApi<String> {
    @Override
    public String fromInterface() {
        return "";
    }

    @Override
    public Result openApi() {
        return new Result();
    }

    @Override
    public void fromDefault() {}

    @Override
    public void scheduledInterface() {}

    @Override
    public Object beanAbstract() {
        return null;
    }

    @Override
    public Object beanDefault() {
        return null;
    }

    @Override
    public void fromSuperclass() {}

    @Override
    public void scheduledSuper() {}

    @Override
    public void postConstructSuper() {}

    @Override
    public Object beanSuper() {
        return null;
    }

    @Override
    public void handle(String event) {}
}
