package com.example.forked;

import com.example.target.BranchTarget;
import com.example.target.GeneratedPoint;
import com.example.target.RoutineJavaTarget;
import com.example.target.RoutineTarget;
import com.example.target.SwitchTarget;

/**
 * The program {@code CoverageAgentOrderTest} runs in a JVM of its own, with this agent and
 * JaCoCo's on the command line in each order. It drives the fixtures whose marks an earlier
 * transformer's probes would hide, and drives each Java conditional one way only, so a jump
 * JaCoCo inverts has one outcome hit and one not. It lives outside {@code com.example.target} so
 * the agent under test leaves it alone.
 */
public final class CoverageAgentOrderMain {
    private CoverageAgentOrderMain() {}

    public static void main(String[] args) {
        GeneratedPoint point = new GeneratedPoint(1, "a");
        point.custom();
        point.equals(new GeneratedPoint(1, "a"));
        point.hashCode();
        point.toString();

        RoutineTarget routine = new RoutineTarget();
        routine.tryFinally(true);
        routine.elvisConstant(null);
        routine.elvisConstant("x");
        routine.countMissing(new String[] {"a", "b"});

        SwitchTarget switches = new SwitchTarget();
        switches.stringWhen("open");
        switches.stringWhen("Aa");
        switches.stringWhen("nothing");

        RoutineJavaTarget routineJava = new RoutineJavaTarget();
        routineJava.tryFinally(true);
        routineJava.earlyReturn("abc");

        new BranchTarget().classify(5);
    }
}
