package dev.otherlode.bootstrap;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Holds the messages of the {@code VerifyError}s the JVM constructs, from the moment a constructor
 * runs until the export thread drains them.
 *
 * <p>The agent weaves both constructors of {@code java.lang.VerifyError} to call {@link
 * #record(String)}. A link-time {@code VerifyError} is constructed while the failing class's
 * initialisation lock is held, so {@link #record} must not block, load, link, allocate beyond one
 * array store, log or throw. It claims a slot with {@code AtomicLong.getAndIncrement}, stores the
 * reference and publishes it with a second increment. A call that finds the ring full counts one
 * overflow and stores nothing. {@link #warm()} runs every path once at install, so no class is
 * loaded or linked from inside a constructor.
 *
 * <p>The ring is a fixed array of {@link #CAPACITY} references. {@link #drain()} swaps in an empty
 * array and reads the one it took, so the ring holds up to {@link #CAPACITY} messages between two
 * drains. A writer that read the old array before the swap may store into it after the drain read
 * it; the drain keeps each array for later drains until it is read in full or has aged out. When it
 * lets an array go it seals it, so a writer that claims a slot afterwards counts an overflow, and it
 * counts each claimed slot it never read as overflow. A slot read is cleared, so a message is held
 * only until it is drained.
 *
 * <p>The JVM verifies a rejected class again, and builds the same message again, each time the
 * class is used, so one rejected class on a busy path would fill the ring. {@link #record} keeps the
 * last messages it stored in a table of {@link #SEEN} entries indexed by hash code and skips a
 * message equal to the one its entry holds. A message is entered only once it is stored, so one the
 * full ring turned away is kept when it comes again, and a race at worst stores a copy. A message
 * stored in a slot that was sealed before the drain read it is counted as overflow and may then be
 * skipped when it comes again, so its class goes unnamed while the run's unplaced count says so.
 */
public final class OtherlodeVerifierMessages {

    /** How many messages the ring holds between two drains. */
    public static final int CAPACITY = 8192;

    /** How many distinct messages {@link #record} remembers, to skip a message it already stored. */
    public static final int SEEN = 64;

    private static final Ring RING = new Ring(CAPACITY);

    private OtherlodeVerifierMessages() {}

    /**
     * Stores {@code message}, the argument a {@code VerifyError} constructor received, or does
     * nothing when it is null. Never blocks and never throws.
     */
    public static void record(String message) {
        RING.record(message);
    }

    /** Returns the messages recorded since the last drain, and removes them. Called from one thread. */
    public static String[] drain() {
        return RING.drain();
    }

    /** Returns how many messages {@link #record} lost since the last call, and resets the count to zero. */
    public static long takeOverflow() {
        return RING.takeOverflow();
    }

    /**
     * Runs every path of the ring once, on a ring of its own, so that a later call from a {@code
     * VerifyError} constructor loads and links nothing. Leaves the shared ring as it is.
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

    /** A bounded ring of message references. Public so a test can use a small capacity. */
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

        /** The array a write claims from. {@link #record} is this, then {@link #claim}, then {@link #store}. */
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

        /** Removes and returns the messages recorded since the last drain. */
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

        /**
         * Lets {@code slots} go: every later claim on it lands past the capacity and counts its own
         * overflow, and each slot claimed before the seal that holds no message yet counts as one.
         */
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
