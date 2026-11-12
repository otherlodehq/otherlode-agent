package com.example.target.inherit;

public class ConcreteController extends BaseController {
    @Override
    public Result openApi() {
        return new Result();
    }
}
