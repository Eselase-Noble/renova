package io.renova.java.fix;

import io.renova.core.engine.BuildError;
import io.renova.core.engine.MigrationContext;
import io.renova.core.engine.VerifyResult;
import io.renova.core.util.Proc;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Builds and tests every Gradle build root ({@code clean build}) and turns compiler errors and failed tests
 * into {@link BuildError}s. A failed test is attributed to the project code it failed in, as for Maven.
 */
final class GradleVerifier {

    private static final Duration TIMEOUT = Duration.ofMinutes(60);
    /** javac through Gradle: {@code /abs/path/File.java:12: error: message}. */
    private static final Pattern COMPILE_ERROR = Pattern.compile("^(/.+?\\.(?:java|kt|groovy)):(\\d+): error: (.*)$");
    /** Problems in the build script itself: {@code Build file '/abs/build.gradle' line: 14}. */
    private static final Pattern SCRIPT_ERROR = Pattern.compile("^(?:Build|Settings) file '(.+?)' line: (\\d+)");
    private static final Pattern WHAT_WENT_WRONG = Pattern.compile("^\\* What went wrong:$");
    private static final Pattern FRAME = Pattern.compile("^\\s+at ([\\w.$]+)\\.[\\w$<>]+\\(([\\w$]+)\\.java:(\\d+)\\)");
    private static final int MAX_MESSAGE = 400;

    private GradleVerifier() {
    }

    static VerifyResult verify(MigrationContext context, List<Path> roots) throws Exception {
        boolean skipTests = "true".equals(context.options().toolOption("verify.skipTests"));
        Path workspace = context.workspace().root();
        List<BuildError> errors = new ArrayList<>();
        StringBuilder log = new StringBuilder();
        boolean success = true;
        for (Path root : roots) {
            List<String> cmd = GradleSupport.baseCommand(context, root);
            cmd.add("clean");
            cmd.add("build");
            if (skipTests) {
                cmd.add("-x");
                cmd.add("test");
            }
            Proc.Result result = Proc.run(cmd, root, TIMEOUT, MavenSupport.environment(context));
            log.append("== ").append(workspace.relativize(root)).append(" (gradle): exit ").append(result.exitCode()).append('\n');
            if (!result.ok()) {
                success = false;
                log.append(result.tail(40)).append('\n');
                errors.addAll(parse(result.output(), workspace));
                errors.addAll(testFailures(workspace, root));
            }
        }
        return new VerifyResult(success, errors, log.toString());
    }

    static List<BuildError> parse(String output, Path workspace) {
        List<BuildError> errors = new ArrayList<>();
        List<String> lines = output.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            Matcher compile = COMPILE_ERROR.matcher(lines.get(i));
            if (compile.find()) {
                add(errors, new BuildError(relative(Path.of(compile.group(1)), workspace), Integer.parseInt(compile.group(2)),
                        compile.group(3).strip()));
                continue;
            }
            Matcher script = SCRIPT_ERROR.matcher(lines.get(i));
            if (script.find()) {
                // The reason follows the "What went wrong" heading, a few lines down.
                String reason = "";
                for (int j = i + 1; j < Math.min(lines.size(), i + 8); j++) {
                    if (WHAT_WENT_WRONG.matcher(lines.get(j)).find() && j + 1 < lines.size()) {
                        reason = lines.get(j + 1).strip() + (j + 2 < lines.size() ? " " + lines.get(j + 2).strip() : "");
                        break;
                    }
                }
                add(errors, new BuildError(relative(Path.of(script.group(1)), workspace), Integer.parseInt(script.group(2)),
                        reason.isBlank() ? "the build script fails here" : reason));
            }
        }
        if (errors.isEmpty()) {
            // Neither a compiler nor a script error: report what Gradle itself says went wrong (failed tests are
            // read from their reports).
            for (int i = 0; i < lines.size(); i++) {
                if (WHAT_WENT_WRONG.matcher(lines.get(i)).find()) {
                    StringBuilder what = new StringBuilder();
                    for (int j = i + 1; j < lines.size() && !lines.get(j).startsWith("* ") && what.length() < MAX_MESSAGE; j++) {
                        what.append(lines.get(j).strip()).append(' ');
                    }
                    if (!what.toString().contains("There were failing tests")) {
                        add(errors, new BuildError(null, 0, what.toString().strip()));
                    }
                }
            }
        }
        return errors;
    }

    /** Failed and errored tests from Gradle's JUnit XML reports (build/test-results). */
    static List<BuildError> testFailures(Path workspace, Path buildRoot) {
        List<Path> reports;
        try (Stream<Path> files = Files.walk(buildRoot)) {
            reports = files.filter(p -> p.getFileName().toString().matches("TEST-.*\\.xml"))
                    .filter(p -> p.toString().replace('\\', '/').contains("/build/test-results/"))
                    .sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
        if (reports.isEmpty()) {
            return List.of();
        }
        Map<String, String> mainSources = sources(workspace, "src/main/java/");
        List<BuildError> errors = new ArrayList<>();
        for (Path report : reports) {
            try {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                Document doc = factory.newDocumentBuilder().parse(report.toFile());
                NodeList cases = doc.getElementsByTagName("testcase");
                for (int i = 0; i < cases.getLength(); i++) {
                    Element test = (Element) cases.item(i);
                    Element failure = first(test, "failure");
                    if (failure == null) {
                        failure = first(test, "error");
                    }
                    if (failure == null) {
                        continue;
                    }
                    String testClass = test.getAttribute("classname");
                    String simple = testClass.substring(testClass.lastIndexOf('.') + 1);
                    String message = failure.getAttribute("message");
                    if (message.isBlank()) {
                        message = failure.getTextContent().lines().findFirst().orElse("").strip();
                    }
                    if (message.length() > MAX_MESSAGE) {
                        message = message.substring(0, MAX_MESSAGE) + "…";
                    }
                    // The project code the test failed in; without one, the class the test is named after.
                    String file = null;
                    int line = 0;
                    for (String frameLine : failure.getTextContent().lines().toList()) {
                        Matcher frame = FRAME.matcher(frameLine);
                        if (frame.find() && mainSources.containsKey(frame.group(1).replaceAll("\\$.*", ""))) {
                            file = mainSources.get(frame.group(1).replaceAll("\\$.*", ""));
                            line = Integer.parseInt(frame.group(3));
                            break;
                        }
                    }
                    if (file == null) {
                        String underTest = testClass.replaceAll("(Tests?|IT)$", "");
                        file = mainSources.get(underTest);
                    }
                    String name = test.getAttribute("name").replaceAll("\\(.*\\)$", "");
                    add(errors, new BuildError(file, line, "test " + simple + "." + name + " failed: " + message));
                }
            } catch (Exception e) {
                // An unreadable report: the build log still says tests failed.
            }
        }
        return errors;
    }

    private static Element first(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }

    /** Fully qualified class name to its project-relative source file, for every file under {@code marker}. */
    private static Map<String, String> sources(Path workspace, String marker) {
        Map<String, String> index = new HashMap<>();
        try (Stream<Path> files = Files.walk(workspace)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                String rel = workspace.relativize(p).toString().replace('\\', '/');
                int at = rel.indexOf(marker);
                if (at >= 0 && !rel.contains("/build/")) {
                    index.put(rel.substring(at + marker.length(), rel.length() - ".java".length()).replace('/', '.'), rel);
                }
            });
        } catch (IOException e) {
            // No index: failures are reported without a file.
        }
        return index;
    }

    private static void add(List<BuildError> errors, BuildError error) {
        if (!errors.contains(error)) {
            errors.add(error);
        }
    }

    private static String relative(Path file, Path workspace) {
        Path absolute = file.toAbsolutePath().normalize();
        Path root = workspace.toAbsolutePath().normalize();
        try {
            // The workspace may be reached through a symbolic link (/tmp on some systems).
            root = root.toRealPath();
            absolute = Files.exists(absolute) ? absolute.toRealPath() : absolute;
        } catch (IOException e) {
            // Compare the paths as given.
        }
        return absolute.startsWith(root) ? root.relativize(absolute).toString().replace('\\', '/') : file.toString();
    }
}
