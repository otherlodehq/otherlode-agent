package com.example.target.named;

import com.example.outside.named.Bus;
import com.example.outside.named.ClassCall;
import com.example.outside.named.ClassRepeated;
import com.example.outside.named.OtherCall;
import com.example.outside.named.RepeatedCall;
import com.example.outside.named.RuntimeCall;
import org.springframework.context.event.EventListener;

/** Methods that carry an adopter's own annotations in each place the option reads. */
public class NamedCallbacks {
    @RuntimeCall
    public void runtimeCall() {}

    @ClassCall
    public void classCall() {}

    @OtherCall
    public void otherCall() {}

    @Bus.Handler
    public void nested() {}

    @InScopeCall
    public void inScope() {}

    @ComposedOfRuntime
    public void composedOfRuntime() {}

    @ComposedOfClass
    public void composedOfClass() {}

    public void onParameter(@RuntimeCall String value) {}

    @EventListener
    public void builtIn() {}

    public void plain() {}

    @RepeatedCall
    @RepeatedCall
    public void repeated() {}

    @ClassRepeated
    @ClassRepeated
    public void classRepeated() {}

    @ClassComposedOfBuiltIn
    public void classComposedOfBuiltIn() {}

    @ClassComposedOfNamed
    public void classComposedOfNamed() {}

    @EventListener
    @RuntimeCall
    public void builtInThenNamed() {}
}
