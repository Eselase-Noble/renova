package io.renova.php.fix;

import io.renova.core.engine.MigrationContext;
import io.renova.core.engine.PlanStep;
import io.renova.core.engine.StageResult;
import io.renova.core.spi.Fixer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fix strategy {@code phpunit-config}: brings a phpunit.xml written for PHPUnit 4 to 9 to what PHPUnit 10 and
 * later accept. Settings that no longer exist are removed (they are the defaults now, or have no effect), and
 * the list of source folders moves from {@code <filter><whitelist>} or {@code <coverage><include>} to
 * {@code <source><include>}. PHPUnit's own {@code --migrate-configuration} does this for files from PHPUnit 9
 * and refuses older ones.
 */
public final class PhpUnitConfigFixer implements Fixer {

    public static final String STRATEGY = "phpunit-config";
    /** Attributes of the root element that PHPUnit 10 removed. */
    private static final List<String> REMOVED = List.of("syntaxCheck", "convertErrorsToExceptions", "convertNoticesToExceptions",
            "convertWarningsToExceptions", "convertDeprecationsToExceptions", "verbose", "printerClass", "printerFile",
            "testSuiteLoaderClass", "testSuiteLoaderFile", "mapTestClassNameToCoveredClassName", "forceCoversAnnotation",
            "beStrictAboutCoversAnnotation", "disallowTestOutput", "strict", "noInteraction", "timeoutForSmallTests",
            "timeoutForMediumTests", "timeoutForLargeTests", "beStrictAboutTodoAnnotatedTests", "beStrictAboutResourceUsageDuringSmallTests");

    @Override
    public String strategy() {
        return STRATEGY;
    }

    @Override
    public StageResult apply(MigrationContext context, List<PlanStep> steps) throws Exception {
        Path root = context.workspace().root();
        List<String> details = new ArrayList<>();
        int changed = 0;
        for (PlanStep step : steps) {
            for (String file : step.files()) {
                Path path = root.resolve(file);
                String before = Files.readString(path, StandardCharsets.UTF_8);
                String after = modernise(before);
                if (!after.equals(before)) {
                    Files.writeString(path, after, StandardCharsets.UTF_8);
                    changed++;
                }
                details.add(step.rule().id() + ": " + file + ": " + (after.equals(before) ? "already correct" : "rewritten for PHPUnit 10 and later"));
            }
        }
        return new StageResult(STRATEGY, StageResult.Status.APPLIED, changed + " PHPUnit configuration file(s) rewritten", details);
    }

    static String modernise(String xml) {
        String out = xml;
        for (String attribute : REMOVED) {
            out = out.replaceAll("\\s+" + attribute + "\\s*=\\s*\"[^\"]*\"", "");
        }
        out = out.replaceAll("\\bbackupStaticAttributes(\\s*=)", "backupStaticProperties$1");
        out = out.replaceAll("\\s+xsi:noNamespaceSchemaLocation\\s*=\\s*\"[^\"]*\"", " xsi:noNamespaceSchemaLocation=\"vendor/phpunit/phpunit/phpunit.xsd\"");
        // <filter><whitelist ...>folders</whitelist></filter> (PHPUnit 4 to 8) and <coverage ...><include>folders</include> (9).
        Matcher whitelist = Pattern.compile("(?s)([ \\t]*)<filter>\\s*<whitelist[^>]*>(.*?)</whitelist>\\s*</filter>").matcher(out);
        if (whitelist.find()) {
            out = out.substring(0, whitelist.start()) + source(whitelist.group(1), whitelist.group(2)) + out.substring(whitelist.end());
        }
        Matcher coverage = Pattern.compile("(?s)([ \\t]*)<coverage[^>]*>(.*?)</coverage>").matcher(out);
        if (coverage.find() && coverage.group(2).contains("<include>")) {
            Matcher include = Pattern.compile("(?s)<include>(.*?)</include>").matcher(coverage.group(2));
            Matcher exclude = Pattern.compile("(?s)<exclude>.*?</exclude>").matcher(coverage.group(2));
            if (include.find()) {
                String rest = coverage.group(2).replace(include.group(), "");
                String excluded = exclude.find() ? exclude.group() : "";
                rest = rest.replace(excluded, "").strip();
                String indent = coverage.group(1);
                String kept = rest.isEmpty() ? "" : "\n" + indent + "<coverage>\n" + indent + "    " + rest + "\n" + indent + "</coverage>";
                out = out.substring(0, coverage.start()) + indent + "<source>\n" + indent + "    <include>" + include.group(1) + "</include>\n"
                        + (excluded.isEmpty() ? "" : indent + "    " + excluded + "\n") + indent + "</source>" + kept + out.substring(coverage.end());
            }
        }
        return out;
    }

    /** The folders of a whitelist as a source element; "exclude" entries inside it keep their place. */
    private static String source(String indent, String folders) {
        Matcher exclude = Pattern.compile("(?s)<exclude>.*?</exclude>").matcher(folders);
        String excluded = exclude.find() ? exclude.group() : "";
        String included = folders.replace(excluded, "").strip().replaceAll("\\R\\s*", "\n" + indent + "        ");
        return indent + "<source>\n" + indent + "    <include>\n" + indent + "        " + included + "\n" + indent + "    </include>\n"
                + (excluded.isEmpty() ? "" : indent + "    " + excluded.strip() + "\n") + indent + "</source>";
    }
}
