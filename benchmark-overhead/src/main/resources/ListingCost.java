import dev.otherlode.classlisting.ClassLister;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Times the agent's class listing: calls {@code ClassLister.list()} five times against the class path
 * in its first argument, with the {@code ;}-separated include and exclude rules in its second and
 * third, and prints one {@code LISTING_COST} line with the first call's CPU and wall milliseconds,
 * the median CPU milliseconds of the other four, and the number of classes listed. Exits 1 when the
 * listing is null.
 */
public final class ListingCost {
    private static final int CALLS = 5;

    /** Runs the five calls and prints the line; see the class description for the arguments. */
    public static void main(String[] args) {
        String classPath = args[0];
        List<String> includes = split(args.length > 1 ? args[1] : "");
        List<String> excludes = split(args.length > 2 ? args[2] : "");
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        double[] cpuMs = new double[CALLS];
        double[] wallMs = new double[CALLS];
        int classes = -1;
        for (int i = 0; i < CALLS; i++) {
            ClassLister lister = new ClassLister(includes, excludes, classPath, "", "");
            long cpu0 = threads.getCurrentThreadCpuTime();
            long wall0 = System.nanoTime();
            List<String> listed = lister.list();
            long cpu1 = threads.getCurrentThreadCpuTime();
            long wall1 = System.nanoTime();
            if (listed == null) {
                System.err.println("ClassLister.list() returned null for " + classPath);
                System.exit(1);
            }
            classes = listed.size();
            cpuMs[i] = (cpu1 - cpu0) / 1e6;
            wallMs[i] = (wall1 - wall0) / 1e6;
        }
        double[] warm = Arrays.copyOfRange(cpuMs, 1, CALLS);
        Arrays.sort(warm);
        double warmMedian = (warm[warm.length / 2 - 1] + warm[warm.length / 2]) / 2;
        System.out.printf(
                java.util.Locale.ROOT,
                "LISTING_COST coldCpuMs=%.3f coldWallMs=%.3f warmCpuMs=%.3f classes=%d%n",
                cpuMs[0], wallMs[0], warmMedian, classes);
    }

    private static List<String> split(String raw) {
        List<String> parts = new ArrayList<>();
        for (String part : raw.split(";")) {
            if (!part.isBlank()) parts.add(part.trim());
        }
        return parts;
    }
}
