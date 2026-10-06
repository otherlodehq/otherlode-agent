package com.example.target.callbacks;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import javax.annotation.PostConstruct;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Methods that carry callback annotations in each place and order the agent reads. */
public class Listeners implements Runnable {
    @Inject
    public Listeners() {}

    @Scheduled
    @Override
    public void run() {}

    @EventListener
    public void onEvent() {}

    @GetMapping
    public String get() {
        return "";
    }

    @Composed
    public void composed() {}

    @DeepComposed
    public void deep() {}

    @Vanished
    public void vanished() {}

    @Loop
    public void looped() {}

    @CycleX
    public void cycleX() {}

    @CycleY
    public void cycleY() {}

    @GetMapping
    public void mappedObserver(@Observes String event) {}

    public void observe(String first, @Observes String event) {}

    public void modelOnParameter(@ModelAttribute String model) {}

    @ModelAttribute
    public void modelOnMethod() {}

    @Observes
    public void observesOnMethod() {}

    @Deprecated
    @Marker
    public void unlisted() {}

    @PostConstruct
    public void classRetained() {}

    @GetMapping
    @EventListener
    public void getFirst() {}

    @EventListener
    @GetMapping
    public void eventFirst() {}

    @Inject
    public void setDependency(String dependency) {}

    @Bean
    public static Object bean() {
        return null;
    }

    @EventListener
    private void hidden() {}

    public void plain() {}

    @Marker
    public void beside(@Marker String value) {}
}
