package io.renova.java.fix;

import io.renova.core.engine.BuildError;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Turns failed tests into build errors that point at the code to fix. Reads the plain-text reports
 * Surefire and Failsafe write to target/*-reports (the same format in Surefire 2 and 3), rather than
 * console output, which differs between versions and omits stack traces.
 *
 * <p>Each failure is attributed to the first stack frame in the project's main sources (for example
 * the class that threw), falling back to the test class: the fix is usually in the code under test or
 * its build file, not in the test.
 */
final class TestReports {

    private static final Pattern TEST_SET = Pattern.compile("^Test set: ([\\w.$]+)");
    /** Surefire 2: {@code method(com.acme.FooTest)  Time elapsed: ... <<< ERROR!} */
    private static final Pattern FAILED_V2 = Pattern.compile("^([\\w$]+)\\(([\\w.$]+)\\)\\s+Time elapsed:.*<<< (FAILURE|ERROR)!");
    /** Surefire 3: {@code com.acme.FooTest.method(ParamTypes) -- Time elapsed: ... <<< ERROR!} */
    private static final Pattern FAILED_V3 = Pattern.compile("^([\\w.$]+)\\.([\\w$]+)(?:\\([^)]*\\))?\\s+--\\s+Time elapsed:.*<<< (FAILURE|ERROR)!");
    private static final int MAX_MESSAGE = 400;
    private static final Pattern FRAME = Pattern.compile("^\\s+at ([\\w.$]+)\\.[\\w$<>]+\\(([\\w$]+)\\.java:(\\d+)\\)");
    private static final int MAX_FRAMES = 4;

    private TestReports() {
    }

    static List<BuildError> parse(Path workspace, Path buildRoot) {
        List<Path> reports;
        try (Stream<Path> files = Files.walk(buildRoot)) {
            reports = files.filter(p -> p.toString().endsWith(".txt"))
                    .filter(p -> p.getParent() != null && p.getParent().getFileName().toString().matches("(surefire|failsafe)-reports"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (reports.isEmpty()) {
            return List.of();
        }
        Map<String, String> mainSources = sourceIndex(workspace, "src/main/java/");
        Map<String, String> testSources = sourceIndex(workspace, "src/test/java/");
        List<BuildError> errors = new ArrayList<>();
        for (Path report : reports) {
            errors.addAll(parseReport(report, mainSources, testSources));
        }
        return errors;
    }

    static List<BuildError> parseReport(Path report, Map<String, String> mainSources, Map<String, String> testSources) {
        List<String> lines;
        try {
            lines = Files.readAllLines(report, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return List.of();
        }
        String testSet = null;
        List<BuildError> errors = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher set = TEST_SET.matcher(lines.get(i));
            if (set.find()) {
                testSet = set.group(1);
                continue;
            }
            String testClass;
            String method;
            Matcher v2 = FAILED_V2.matcher(lines.get(i));
            Matcher v3 = FAILED_V3.matcher(lines.get(i));
            if (v2.find()) {
                method = v2.group(1);
                testClass = v2.group(2);
            } else if (v3.find()) {
                testClass = v3.group(1);
                method = v3.group(2);
            } else {
                continue;
            }
            String test = simple(testClass != null ? testClass : String.valueOf(testSet)) + "." + method;

            // The exception line(s) up to the first stack frame; assertion messages may span blank lines.
            StringBuilder exception = new StringBuilder();
            int j = i + 1;
            while (j < lines.size() && !FRAME.matcher(lines.get(j)).find()
                    && !FAILED_V2.matcher(lines.get(j)).find() && !FAILED_V3.matcher(lines.get(j)).find()) {
                if (!lines.get(j).isBlank() && exception.length() < MAX_MESSAGE) {
                    exception.append(exception.isEmpty() ? "" : " ").append(lines.get(j).strip());
                }
                j++;
            }
            if (exception.length() > MAX_MESSAGE) {
                exception.setLength(MAX_MESSAGE);
                exception.append("…");
            }
            String file = null;
            int line = 0;
            String testFile = null;
            int testLine = 0;
            List<String> frames = new ArrayList<>();
            for (; j < lines.size(); j++) {
                Matcher frame = FRAME.matcher(lines.get(j));
                if (!frame.find()) {
                    if (FAILED_V2.matcher(lines.get(j)).find() || FAILED_V3.matcher(lines.get(j)).find()) {
                        break;
                    }
                    continue;
                }
                String outer = frame.group(1).replaceAll("\\$.*", "");
                if (mainSources.containsKey(outer)) {
                    if (file == null) {
                        file = mainSources.get(outer);
                        line = Integer.parseInt(frame.group(3));
                    }
                    if (frames.size() < MAX_FRAMES) {
                        frames.add(simple(outer) + ".java:" + frame.group(3));
                    }
                } else if (testSources.containsKey(outer) && testFile == null) {
                    testFile = testSources.get(outer);
                    testLine = Integer.parseInt(frame.group(3));
                }
            }
            if (file == null) {
                file = testFile != null ? testFile : testClass == null ? null : testSources.get(testClass);
                line = testFile != null ? testLine : 0;
            }
            errors.add(new BuildError(file, line, "test " + test + " failed: " + exception
                    + (frames.isEmpty() ? "" : " (at " + String.join(" ← ", frames) + ")")));
            i = j - 1;
        }
        return errors;
    }

    /** Fully qualified class name to workspace-relative path, for sources under the given root. */
    private static Map<String, String> sourceIndex(Path workspace, String sourceRoot) {
        Map<String, String> index = new HashMap<>();
        try (Stream<Path> files = Files.walk(workspace)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                String rel = workspace.relativize(p).toString().replace('\\', '/');
                int at = rel.indexOf(sourceRoot);
                if (at >= 0 && !rel.contains("/target/")) {
                    String fqn = rel.substring(at + sourceRoot.length(), rel.length() - ".java".length()).replace('/', '.');
                    index.putIfAbsent(fqn, rel);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return index;
    }

    private static String simple(String fqn) {
        return fqn.substring(fqn.lastIndexOf('.') + 1);
    }
}
