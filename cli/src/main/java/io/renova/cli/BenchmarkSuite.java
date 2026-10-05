package io.renova.cli;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A benchmark: legacy apps to migrate, the configurations to migrate them with, and checks that
 * score each migrated copy beyond "the build passes" (for example that upload limits kept their values).
 *
 * <pre>
 * id: java-apps
 * appsDir: ../../renova-test-apps          # relative to this file; --apps overrides it
 * apps:
 *   - id: inventory-platform
 *     path: inventory-platform
 *     checks:
 *       - { file: inventory-web/src/main/webapp/WEB-INF/web.xml, contains: "&lt;max-file-size&gt;10485760" }
 *       - { file: "**&#47;pom.xml", absent: "--add-opens", why: "no encapsulation workarounds" }
 * configurations:
 *   - { id: deterministic }
 *   - { id: ai, ai: true }
 *   - { id: ai-rag, ai: true, rag: true }
 * </pre>
 */
record BenchmarkSuite(String id, String appsDir, List<App> apps, List<Configuration> configurations) {

    /** @param playbook playbook id or file; the default playbook for the project when absent */
    record App(String id, String path, String playbook, List<Check> checks) {
        App {
            checks = checks == null ? List.of() : List.copyOf(checks);
        }
    }

    /**
     * One property of the migrated copy. {@code file} is a path or a glob within the copy. Exactly
     * one of {@code contains} (some matching file contains the text), {@code absent} (no matching file
     * contains it) or {@code matches} (some matching file matches the regex) is given.
     */
    record Check(String file, String contains, String absent, String matches, String why) {
        String describe() {
            String what = contains != null ? "contains \"" + contains + "\""
                    : absent != null ? "has no \"" + absent + "\"" : "matches /" + matches + "/";
            return file + " " + what + (why == null ? "" : " (" + why + ")");
        }
    }

    /**
     * @param ai        use the configured AI provider; otherwise AI steps are left as manual work
     * @param rag       retrieval for AI requests
     * @param skip      strategies to skip, as {@code migrate --skip}
     * @param skipTests build without running the apps' tests
     */
    record Configuration(String id, boolean ai, boolean rag, List<String> skip, boolean skipTests) {
        Configuration {
            skip = skip == null ? List.of() : List.copyOf(skip);
        }
    }

    BenchmarkSuite {
        apps = apps == null ? List.of() : List.copyOf(apps);
        configurations = configurations == null ? List.of() : List.copyOf(configurations);
    }

    static BenchmarkSuite load(Path file) throws IOException {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory()).configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        BenchmarkSuite suite = yaml.readValue(file.toFile(), BenchmarkSuite.class);
        suite.validate(file);
        return suite;
    }

    private void validate(Path file) {
        if (apps.isEmpty() || configurations.isEmpty()) {
            throw new IllegalArgumentException(file + ": a suite needs apps and configurations");
        }
        Set<String> ids = new HashSet<>();
        for (App app : apps) {
            if (app.id() == null || app.path() == null || !ids.add("app:" + app.id())) {
                throw new IllegalArgumentException(file + ": each app needs a unique id and a path");
            }
            for (Check check : app.checks()) {
                long given = java.util.stream.Stream.of(check.contains(), check.absent(), check.matches())
                        .filter(java.util.Objects::nonNull).count();
                if (check.file() == null || given != 1) {
                    throw new IllegalArgumentException(file + ": app " + app.id()
                            + ": each check needs a file and exactly one of contains, absent or matches");
                }
            }
        }
        for (Configuration c : configurations) {
            if (c.id() == null || !ids.add("config:" + c.id())) {
                throw new IllegalArgumentException(file + ": each configuration needs a unique id");
            }
            if (c.rag() && !c.ai()) {
                throw new IllegalArgumentException(file + ": configuration " + c.id() + " enables rag without ai");
            }
        }
    }
}
