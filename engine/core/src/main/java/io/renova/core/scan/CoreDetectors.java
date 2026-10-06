package io.renova.core.scan;

import io.renova.core.model.Finding;
import io.renova.core.playbook.Params;
import io.renova.core.playbook.Rule;
import io.renova.core.spi.Detector;
import io.renova.core.spi.DetectorFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Detectors that work on any ecosystem because they only look at files and text. */
public final class CoreDetectors {

    private CoreDetectors() {
    }

    public static List<DetectorFactory> all() {
        return List.of(new FileExists(), new FileContains());
    }

    /** {@code type: fileExists, include: [glob...]} — one finding per matching file. */
    static final class FileExists implements DetectorFactory {
        @Override
        public String type() {
            return "fileExists";
        }

        @Override
        public Detector create(Rule rule) {
            List<String> include = rule.detectParams().requiredStrings("include");
            return ctx -> ctx.files(include).stream()
                    .map(f -> ctx.finding(rule, f, 0, null))
                    .toList();
        }
    }

    /**
     * {@code type: fileContains, include: [glob...], pattern: regex, exclude?: regex, requires?: regex, unless?: regex}
     * — one finding per matching line, skipping lines that also match {@code exclude}. With {@code requires},
     * only files containing a line that matches it are checked (for example an import that tells which
     * {@code Assert} class a file uses); with {@code unless}, files containing a line that matches it are
     * skipped (the thing the rule asks for is already there).
     */
    static final class FileContains implements DetectorFactory {
        @Override
        public String type() {
            return "fileContains";
        }

        @Override
        public Detector create(Rule rule) {
            Params params = rule.detectParams();
            List<String> include = params.requiredStrings("include");
            Pattern pattern = Pattern.compile(params.string("pattern"));
            Pattern exclude = params.optString("exclude").map(Pattern::compile).orElse(null);
            Pattern requires = params.optString("requires").map(Pattern::compile).orElse(null);
            Pattern unless = params.optString("unless").map(Pattern::compile).orElse(null);
            return ctx -> {
                List<Finding> findings = new ArrayList<>();
                for (Path file : ctx.files(include)) {
                    List<String> lines = ctx.lines(file);
                    if (requires != null && lines.stream().noneMatch(l -> requires.matcher(l).find())) {
                        continue;
                    }
                    if (unless != null && lines.stream().anyMatch(l -> unless.matcher(l).find())) {
                        continue;
                    }
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        Matcher m = pattern.matcher(line);
                        if (m.find() && (exclude == null || !exclude.matcher(line).find())) {
                            findings.add(ctx.finding(rule, file, i + 1, line));
                        }
                    }
                }
                return findings;
            };
        }
    }
}
