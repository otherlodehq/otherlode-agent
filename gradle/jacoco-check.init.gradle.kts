// Applies the `jacoco` plugin to every Java project, so every test JVM runs with JaCoCo's agent on
// its command line beside whatever agent the test attaches. CI runs `check` with this script on
// each push. A test that fails only here is a finding about running beside a coverage agent, to be
// fixed rather than excluded.
//
//     ./gradlew --init-script gradle/jacoco-check.init.gradle.kts check
allprojects { plugins.withId("java") { apply(plugin = "jacoco") } }
