package dev.otherlode.dependencies;

import java.nio.file.Path;
import java.security.CodeSource;

/**
 * Run in a forked JVM by {@code ClasspathWalkerJvmTest}. For each class name given, prints the name,
 * a tab, and the path the system loader reads the class from, or {@code absent}. It is Java so the
 * forked JVM needs no Kotlin standard library.
 */
public final class ClassOriginProbe {
    /** What the probe prints for a class the system loader cannot find. */
    public static final String ABSENT = "absent";

    private ClassOriginProbe() {}

    public static void main(String[] names) throws Exception {
        for (String name : names) {
            String origin;
            try {
                Class<?> type = Class.forName(name, false, ClassLoader.getSystemClassLoader());
                CodeSource source = type.getProtectionDomain().getCodeSource();
                origin = Path.of(source.getLocation().toURI()).toString();
            } catch (ClassNotFoundException e) {
                origin = ABSENT;
            }
            System.out.println(name + "\t" + origin);
        }
    }
}
