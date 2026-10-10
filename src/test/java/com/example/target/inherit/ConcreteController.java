package com.example.target.inherit;

/** Overrides a base controller's unannotated method, whose annotation sits on the interface the base implements. */
public class ConcreteController extends BaseController {
    @Override
    public Result openApi() {
        return new Result();
    }
}
