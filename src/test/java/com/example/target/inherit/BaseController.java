package com.example.target.inherit;

public class BaseController implements InheritApi {
    @Override
    public String fromInterface() {
        return "";
    }

    @Override
    public Result openApi() {
        return new Result();
    }

    @Override
    public void scheduledInterface() {}

    @Override
    public Object beanAbstract() {
        return null;
    }
}
