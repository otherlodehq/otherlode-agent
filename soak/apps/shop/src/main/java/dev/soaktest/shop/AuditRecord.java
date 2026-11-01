package dev.soaktest.shop;

import java.util.concurrent.atomic.AtomicLong;

public final class AuditRecord {
    private static final AtomicLong COUNTER = new AtomicLong();

    private final String who;

    public AuditRecord(String who) {
        this.who = who;
    }

    public static long next() {
        return COUNTER.incrementAndGet();
    }

    public String describe() {
        return who + "#" + next();
    }
}
