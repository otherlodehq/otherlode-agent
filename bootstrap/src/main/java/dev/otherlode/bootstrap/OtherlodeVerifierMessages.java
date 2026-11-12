package dev.otherlode.bootstrap;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Holds {@code VerifyError} messages passed to {@link #record(String)} until {@link #drain()} takes them.
 *
 * <p>The ring holds up to {@link #CAPACITY} messages between two drains. A call that finds it
 * full counts one overflow and stores nothing. A message equal to one stored recently is skipped.
 */
public final class OtherlodeVerifierMessages {
    /** How many messages the ring holds between two drains. */
    public static final int CAPACITY = 8192;

    /** How many slots {@link #record} has for the recently stored messages it skips a repeat of. */
    public static final int SEEN = 64;

    private static final Ring RING = new Ring(CAPACITY);

    private OtherlodeVerifierMessages() {}

    /**
     * Stores {@code message}, a {@code VerifyError}'s message. Does nothing when it is null or equal
     * to a message stored recently, and counts one overflow when the ring is full. Takes no lock and
     * never throws.
     */
    public static void record(String message) {
        RING.record(message);
    }

    /**
     * Returns and removes the messages recorded since the last drain. A message behind a slot still
     * being written waits for a later drain, and counts as overflow if that slot is still empty two
     * drains later.
     */
    public static String[] drain() {
        return RING.drain();
    }

    /** Returns how many messages {@link #record} lost since the last call, and resets the count to zero. */
    public static long takeOverflow() {
        return RING.takeOverflow();
    }

    /**
     * Runs the ring's paths once on a ring of its own, so a later {@link #record} has its classes
     * loaded and linked. Call it before {@link #record} can run. Leaves the shared ring as it is.
     */
    public static void warm() {
        Ring probe = new Ring(2);
        probe.record(null);
        probe.record("a");
        probe.record("a");
        probe.record("b");
        probe.record("c");
        probe.drain();
        probe.drain();
        probe.drain();
        probe.drain();
        probe.takeOverflow();
        RING.record(null);
    }

    /** A bounded ring of message references. */
    public static final class Ring {
        private static final int MAX_DRAINS_WHILE_INCOMPLETE = 3;

        private final int capacity;
        private final String[] seen = new String[SEEN];
        private final AtomicLong overflow = new AtomicLong();
        private volatile Slots current;
        private final List<Slots> retired = new ArrayList<>();

        /** Creates a ring that holds {@code capacity} messages between drains. */
        public Ring(int capacity) {
            this.capacity = capacity;
            this.current = new Slots(capacity);
        }

        /** Stores {@code message}; see {@link OtherlodeVerifierMessages#record(String)}. */
        public void record(String message) {
            // Can run inside a VerifyError constructor under a class's initialisation lock: it must
            // not block, load or link a class, allocate, log or throw.
            if (message == null) {
                return;
            }
            int entry = message.hashCode() & (SEEN - 1);
            if (message.equals(seen[entry])) {
                return;
            }
            Object target = writeTarget();
            long index = claim(target);
            if (index >= 0) {
                store(target, index, message);
                seen[entry] = message;
            }
        }

        /** Returns the slots a write claims from; pass them to {@link #claim} and then {@link #store}. */
        public Object writeTarget() {
            return current;
        }

        /** Claims a slot in {@code target}, or counts one overflow and returns -1 when none is left. */
        public long claim(Object target) {
            long index = ((Slots) target).claimed.getAndIncrement();
            if (index >= capacity) {
                overflow.getAndIncrement();
                return -1;
            }
            return index;
        }

        /** Stores {@code message} in the slot {@link #claim} returned and publishes it. */
        public void store(Object target, long index, String message) {
            Slots slots = (Slots) target;
            slots.messages[(int) index] = message;
            slots.published.getAndIncrement();
        }

        /**
         * Returns and removes the messages recorded since the last drain; see {@link
         * OtherlodeVerifierMessages#drain()}.
         */
        public synchronized String[] drain() {
            List<String> out = new ArrayList<>();
            for (Iterator<Slots> it = retired.iterator(); it.hasNext(); ) {
                Slots slots = it.next();
                slots.drains++;
                read(slots, out);
                boolean complete = slots.read >= Math.min(slots.claimed.get(), capacity);
                if ((slots.drains >= 2 && complete) || slots.drains >= MAX_DRAINS_WHILE_INCOMPLETE) {
                    seal(slots, out);
                    it.remove();
                }
            }
            Slots taken = current;
            if (taken.claimed.get() != 0) {
                current = new Slots(capacity);
                read(taken, out);
                taken.drains = 1;
                retired.add(taken);
            }
            return out.toArray(new String[0]);
        }

        /** Returns the overflow count and resets it. */
        public long takeOverflow() {
            return overflow.getAndSet(0);
        }

        private void read(Slots slots, List<String> out) {
            slots.published.get();
            long limit = Math.min(slots.claimed.get(), capacity);
            while (slots.read < limit && slots.read < capacity) {
                String message = slots.messages[slots.read];
                if (message == null) {
                    break;
                }
                out.add(message);
                slots.messages[slots.read] = null;
                slots.read++;
            }
        }

        private void seal(Slots slots, List<String> out) {
            long sealed = Math.min(slots.claimed.getAndAdd(capacity), capacity);
            read(slots, out);
            overflow.getAndAdd(sealed - slots.read);
        }
    }

    private static final class Slots {
        final String[] messages;
        final AtomicLong claimed = new AtomicLong();
        final AtomicLong published = new AtomicLong();
        int read;
        int drains;

        Slots(int capacity) {
            this.messages = new String[capacity];
        }
    }
}
