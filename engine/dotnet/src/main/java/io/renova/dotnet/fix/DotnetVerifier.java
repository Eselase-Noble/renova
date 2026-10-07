package io.renova.dotnet.fix;

import io.renova.core.engine.BuildError;
import io.renova.core.engine.MigrationContext;
import io.renova.core.engine.VerifyResult;
import io.renova.core.model.Module;
import io.renova.core.spi.Verifier;
import io.renova.core.util.Proc;
import io.renova.core.util.Versions;
import io.renova.dotnet.DotnetPlugin;
import io.renova.dotnet.Sources;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Builds the migrated workspace with {@code dotnet build} and runs its tests with {@code dotnet test}: each
 * solution in the workspace's root folder, or without one each project no other project refers to. Compiler
 * and NuGet errors and failed tests become errors the report and the repair loop can use.
 */
public final class DotnetVerifier implements Verifier {

    private static final Duration TIMEOUT = Duration.ofMinutes(60);
    /** {@code /path/File.cs(12,34): error CS0246: message [/path/App.csproj]} */
    private static final Pattern SOURCE_ERROR = Pattern.compile("^\\s*(.+?)\\((\\d+),\\d+\\): error (\\w+): (.*?)(?: \\[[^\\]]+])?$");
    /** {@code /path/App.csproj : error NU1202: message [/path/App.sln]} */
    private static final Pattern PROJECT_ERROR = Pattern.compile("^\\s*(.+?\\.(?:csproj|vbproj|sln|slnx|props|targets))\\s*: error (\\w+): (.*?)(?: \\[[^\\]]+])?$");
    /** {@code vbc : error BC30002: message [/path/App.vbproj]}: a tool's error about the project as a whole. */
    private static final Pattern TOOL_ERROR = Pattern.compile("^\\s*[\\w.]+\\s*: error (\\w+): (.*?) \\[([^\\]]+)]$");
    private static final Pattern RESULT = Pattern.compile("(?s)<UnitTestResult\\b([^>]*?)(?:/>|>(.*?)</UnitTestResult>)");
    private static final Pattern FRAME = Pattern.compile("(?m)^\\s*at .*? in (.+?):line (\\d+)\\s*$");

    @Override
    public void preflight(MigrationContext context) {
        String target = Dotnet.target(context);
        Optional<Path> dotnet = Dotnet.executable();
        String how = " Install the .NET SDK from https://dotnet.microsoft.com/download (Renova looks on the PATH, in DOTNET_ROOT, "
                + "~/.dotnet and the usual install folders; RENOVA_DOTNET names another one). Use --no-verify to migrate without building.";
        if (dotnet.isEmpty()) {
            throw new IllegalStateException("The .NET SDK is not installed on this machine, and the migrated project is built with it." + how);
        }
        List<String> sdks = Dotnet.sdks(dotnet.get());
        if (target != null && sdks.stream().noneMatch(v -> !Versions.isBelow(v, target))) {
            throw new IllegalStateException("This migration targets .NET " + target + ", and no .NET SDK " + target + " or newer is installed "
                    + "(found: " + (sdks.isEmpty() ? "none" : String.join(", ", sdks)) + ")." + how);
        }
    }

    @Override
    public VerifyResult verify(MigrationContext context) throws Exception {
        Path workspace = context.workspace().root();
        Path dotnet = Dotnet.executable().orElseThrow(() -> new IOException("The .NET SDK is not installed"));
        boolean skipTests = "true".equals(context.options().toolOption("verify.skipTests"));
        // The workspace as it is now: a stage may have changed which projects are tests.
        List<Module> modules = new DotnetPlugin().model(workspace).modules();
        StringBuilder log = new StringBuilder();
        boolean hasTests = modules.stream().anyMatch(m -> Boolean.TRUE.equals(m.fact("test")));
        // Windows Forms and WPF compile anywhere with the Windows targeting pack and run on Windows only.
        boolean onWindows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        boolean windowsProjects = modules.stream().anyMatch(io.renova.dotnet.detect.WindowsReferenceDetector::windows);
        boolean elsewhere = windowsProjects && !onWindows;
        List<Module> runnableTests = modules.stream().filter(m -> Boolean.TRUE.equals(m.fact("test"))
                && !io.renova.dotnet.detect.WindowsReferenceDetector.windows(m)).toList();
        if (elsewhere) {
            hasTests = !runnableTests.isEmpty();
            log.append(VerifyResult.NOTE).append("Projects that target Windows (Windows Forms, WPF) were compiled with the Windows "
                    + "targeting pack. Their tests and the applications themselves run on Windows only")
                    .append(runnableTests.isEmpty() ? ", so no test was run on this machine" : ", so only the other tests were run here")
                    .append(": build and test the migrated copy on Windows before relying on it.\n");
        }
        List<BuildError> errors = new ArrayList<>();
        boolean success = true;
        boolean testedHere = false;
        int ran = 0;
        int number = 0;
        for (Path root : buildRoots(workspace, modules)) {
            String name = workspace.relativize(root).toString().replace('\\', '/');
            List<String> buildCommand = new ArrayList<>(List.of(dotnet.toString(), "build", root.toString(), "--nologo", "-v:q",
                    "-clp:NoSummary", "-p:UseSharedCompilation=false"));
            if (elsewhere) {
                buildCommand.add("-p:EnableWindowsTargeting=true");
            }
            Proc.Result build = Proc.run(buildCommand, workspace, TIMEOUT, Dotnet.environment(dotnet));
            log.append("== ").append(name).append(": build exit ").append(build.exitCode()).append('\n');
            if (!build.ok()) {
                success = false;
                log.append(build.tail(40)).append('\n');
                List<BuildError> found = parse(build.output(), workspace);
                errors.addAll(found.isEmpty() ? List.of(new BuildError(name, 0, said(build.output()))) : found);
                continue;
            }
            if (skipTests || !hasTests || (elsewhere && testedHere)) { // off Windows the test projects are run once, not per root
                continue;
            }
            // Off Windows only the test projects that can run here, one by one; otherwise the whole root.
            List<Path> testRoots = elsewhere ? runnableTests.stream().map(m -> workspace.resolve(m.buildFile())).toList() : List.of(root);
            for (Path testRoot : testRoots) {
                Path results = context.workspace().lcDir().resolve("test-results").resolve(String.valueOf(++number));
                deleteRecursively(results);
                Proc.Result test = Proc.run(List.of(dotnet.toString(), "test", testRoot.toString(), "--no-build", "--nologo",
                        "--logger", "trx", "--results-directory", results.toString()), workspace, TIMEOUT, Dotnet.environment(dotnet));
                String testName = workspace.relativize(testRoot).toString().replace('\\', '/');
                log.append("== ").append(testName).append(": test exit ").append(test.exitCode()).append('\n');
                TestRun run = testResults(results, workspace);
                ran += run.ran();
                if (!test.ok()) {
                    success = false;
                    log.append(test.tail(40)).append('\n');
                    errors.addAll(run.failures().isEmpty() ? List.of(new BuildError(testName, 0, said(test.output()))) : run.failures());
                }
            }
            testedHere = true;
        }
        if (success && hasTests && !skipTests && ran == 0) {
            // A passing build proves nothing if it stopped running the tests.
            success = false;
            String project = modules.stream().filter(m -> Boolean.TRUE.equals(m.fact("test"))).findFirst().orElseThrow().buildFile();
            errors.add(new BuildError(project, 0, "the build passes but ran none of the project's tests: a test project needs "
                    + "Microsoft.NET.Test.Sdk and its framework's test adapter for dotnet test to find them"));
            log.append("no tests ran\n");
        }
        if (success && ran > 0) {
            log.append(VerifyResult.NOTE).append(ran).append(" test(s) ran and passed.\n");
        }
        return new VerifyResult(success, errors.stream().distinct().toList(), log.toString());
    }

    /** Solutions in the root folder; without one, the projects nothing else refers to. */
    static List<Path> buildRoots(Path workspace, List<Module> modules) throws IOException {
        try (Stream<Path> files = Files.list(workspace)) {
            List<Path> solutions = files.filter(f -> f.toString().endsWith(".sln") || f.toString().endsWith(".slnx")).sorted().toList();
            if (!solutions.isEmpty()) {
                return solutions;
            }
        }
        List<String> referenced = new ArrayList<>();
        for (Module m : modules) {
            if (m.fact("projectReferences") instanceof List<?> references) {
                Path dir = workspace.resolve(m.path());
                references.forEach(r -> referenced.add(workspace.relativize(dir.resolve(r.toString()).normalize()).toString().replace('\\', '/')));
            }
        }
        return modules.stream().filter(m -> !referenced.contains(m.buildFile())).map(m -> workspace.resolve(m.buildFile())).toList();
    }

    static List<BuildError> parse(String output, Path workspace) {
        List<BuildError> errors = new ArrayList<>();
        for (String line : output.lines().toList()) {
            Matcher source = SOURCE_ERROR.matcher(line);
            Matcher project = PROJECT_ERROR.matcher(line);
            Matcher tool = TOOL_ERROR.matcher(line);
            BuildError error = null;
            if (source.matches()) {
                error = new BuildError(relative(source.group(1), workspace), Integer.parseInt(source.group(2)),
                        source.group(3) + ": " + source.group(4).strip());
            } else if (project.matches()) {
                error = new BuildError(relative(project.group(1), workspace), 0, project.group(2) + ": " + project.group(3).strip());
            } else if (tool.matches()) {
                error = new BuildError(relative(tool.group(3), workspace), 0, tool.group(1) + ": " + tool.group(2).strip());
            }
            if (error != null && !errors.contains(error)) {
                errors.add(error);
            }
        }
        return errors;
    }

    record TestRun(int ran, List<BuildError> failures) {
    }

    /** Reads the .trx files {@code dotnet test} wrote: how many tests ran, and each failure with where it threw. */
    static TestRun testResults(Path results, Path workspace) throws IOException {
        if (!Files.isDirectory(results)) {
            return new TestRun(0, List.of());
        }
        int ran = 0;
        List<BuildError> failures = new ArrayList<>();
        try (Stream<Path> files = Files.walk(results)) {
            for (Path trx : files.filter(f -> f.toString().endsWith(".trx")).sorted().toList()) {
                Matcher result = RESULT.matcher(Files.readString(trx, StandardCharsets.UTF_8));
                while (result.find()) {
                    ran++;
                    if (!"Failed".equals(attribute(result.group(1), "outcome"))) {
                        continue;
                    }
                    String body = result.group(2) == null ? "" : result.group(2);
                    String message = unescape(element(body, "Message")).strip().replaceAll("\\s*\\R\\s*", " ");
                    String file = null;
                    int line = 0;
                    Matcher frame = FRAME.matcher(unescape(element(body, "StackTrace")));
                    while (frame.find()) {
                        String at = relative(frame.group(1), workspace);
                        if (Path.of(at).isAbsolute()) {
                            continue; // not this project's code
                        }
                        boolean test = new DotnetPlugin().isTestFile(at);
                        if (file == null || !test) {
                            file = at;
                            line = Integer.parseInt(frame.group(2));
                        }
                        if (!test) {
                            break; // the project code that threw, rather than the test that called it
                        }
                    }
                    failures.add(new BuildError(file, line, "test " + attribute(result.group(1), "testName") + " failed: "
                            + (message.length() > 400 ? message.substring(0, 400) + "…" : message)));
                }
            }
        }
        return new TestRun(ran, failures);
    }

    /** The build's own words when nothing above could read them. */
    private static String said(String output) {
        List<String> lines = output.lines().map(String::strip).filter(l -> l.contains("error") || l.contains("Error")).distinct().limit(4).toList();
        return lines.isEmpty() ? "the build failed without saying why; see the build log" : String.join(" | ", lines);
    }

    private static String relative(String file, Path workspace) {
        Path path = Path.of(file.strip());
        Path root = workspace.toAbsolutePath().normalize();
        if (!path.isAbsolute()) {
            return file.strip().replace('\\', '/');
        }
        return path.normalize().startsWith(root) ? root.relativize(path.normalize()).toString().replace('\\', '/') : path.toString();
    }

    private static String attribute(String attributes, String name) {
        Matcher m = Pattern.compile("\\b" + name + "=\"([^\"]*)\"").matcher(attributes);
        return m.find() ? unescape(m.group(1)) : "";
    }

    private static String element(String xml, String name) {
        Matcher m = Pattern.compile("(?s)<" + name + ">(.*?)</" + name + ">").matcher(xml);
        return m.find() ? m.group(1) : "";
    }

    private static String unescape(String xml) {
        return xml.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&#xD;", "").replace("&#xA;", "\n").replace("&amp;", "&");
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    /** Whether a path is under a folder the build produces; such files are never reported. */
    static boolean produced(String file) {
        return Sources.produced(Path.of(file));
    }
}
