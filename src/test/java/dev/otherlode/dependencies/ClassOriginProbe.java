package dev.otherlode.dependencies;

import java.nio.file.Path;
import java.security.CodeSource;

// Java, not Kotlin, so the forked JVM that runs it needs no Kotlin standard library.
public final class ClassOriginProbe {
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
