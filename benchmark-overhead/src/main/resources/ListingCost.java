import dev.otherlode.classlisting.ClassLister;
import dev.otherlode.shaded.kotlin.Unit;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Times the agent's class listing: calls {@code ClassLister.list()} five times against the class path
 * in its first argument, with the {@code ;}-separated include and exclude rules in its second and
 * third, and prints one {@code LISTING_COST} line with the first call's CPU and wall milliseconds,
 * the median CPU milliseconds of the other four, and the number of classes listed. Exits 1 when the
 * listing is null or a call throws.
 */
public final class ListingCost {
    private static final int CALLS = 5;

    /** Runs the five calls and prints the line; see the class description for the arguments. */
    public static void main(String[] args) throws InterruptedException {
        String classPath = args[0];
        List<String> includes = split(args.length > 1 ? args[1] : "");
        List<String> excludes = split(args.length > 2 ? args[2] : "");
        double[] cpuMs = new double[CALLS];
        double[] wallMs = new double[CALLS];
        int[] classes = {-1};
        ClassLister[] listers = new ClassLister[CALLS];
        for (int i = 0; i < CALLS; i++) {
            listers[i] = lister(classPath, includes, excludes);
        }
        CountDownLatch coldDone = new CountDownLatch(1);
        CountDownLatch coldRead = new CountDownLatch(1);
        Thread listing = new Thread(() -> {
            try {
                long wall0 = System.nanoTime();
                classes[0] = list(listers[0], classPath);
                wallMs[0] = (System.nanoTime() - wall0) / 1e6;
                coldDone.countDown();
                coldRead.await();
                for (int i = 1; i < CALLS; i++) {
                    long cpu0 = ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
                    long wall1 = System.nanoTime();
                    classes[0] = list(listers[i], classPath);
                    wallMs[i] = (System.nanoTime() - wall1) / 1e6;
                    cpuMs[i] = (ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime() - cpu0) / 1e6;
                }
            } catch (Throwable t) {
                t.printStackTrace();
                System.exit(1);
            }
        }, "otherlode-listing-cost");
        listing.setDaemon(true);
        listing.start();
        coldDone.await();
        cpuMs[0] = ManagementFactory.getThreadMXBean().getThreadCpuTime(listing.threadId()) / 1e6;
        coldRead.countDown();
        listing.join();
        double[] warm = Arrays.copyOfRange(cpuMs, 1, CALLS);
        Arrays.sort(warm);
        double warmMedian = (warm[warm.length / 2 - 1] + warm[warm.length / 2]) / 2;
        System.out.printf(
                java.util.Locale.ROOT,
                "LISTING_COST coldCpuMs=%.3f coldWallMs=%.3f warmCpuMs=%.3f classes=%d%n",
                cpuMs[0], wallMs[0], warmMedian, classes[0]);
    }

    private static ClassLister lister(String classPath, List<String> includes, List<String> excludes) {
        return new ClassLister(
                includes,
                excludes,
                classPath,
                "",
                "",
                "",
                () -> ManagementFactory.getRuntimeMXBean().getInputArguments(),
                (readable, exported) -> Unit.INSTANCE);
    }

    private static int list(ClassLister lister, String classPath) {
        List<String> listed = lister.list();
        if (listed == null) {
            System.err.println("ClassLister.list() returned null for " + classPath);
            System.exit(1);
        }
        return listed.size();
    }

    private static List<String> split(String raw) {
        List<String> parts = new ArrayList<>();
        for (String part : raw.split(";")) {
            if (!part.isBlank()) parts.add(part.trim());
        }
        return parts;
    }
}
