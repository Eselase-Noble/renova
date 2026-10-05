package io.renova.core.engine;

import io.renova.core.playbook.FixSpec;
import io.renova.core.scan.ScanContext;
import io.renova.core.spi.Fixer;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text replacement driven entirely by the playbook, for files AST tools do not parse (JSP, TLD,
 * XML, scripts), or for narrow source fixes a recipe missed. Files keep their original encoding.
 * A rule gives either one {@code find}/{@code replace} pair or, under
 * {@code params.replacements}, a list of {@code {find, replace}} pairs applied in order; {@code regex}
 * applies to all of them.
 */
public final class ReplaceFixer implements Fixer {

    @Override
    public String strategy() {
        return FixSpec.REPLACE;
    }

    @Override
    public StageResult apply(MigrationContext context, List<PlanStep> steps) throws Exception {
        Path root = context.workspace().root();
        List<String> details = new ArrayList<>();
        int changedFiles = 0;
        for (PlanStep step : steps) {
            FixSpec fix = step.rule().fix();
            List<Replacement> replacements = replacements(step.rule().id(), fix);
            int stepFiles = 0;
            for (String file : step.files()) {
                Path path = root.resolve(file);
                if (!Files.isRegularFile(path) || !ScanContext.matches(fix.include(), Path.of(file))) {
                    continue;
                }
                Charset charset = charsetOf(path);
                String before = Files.readString(path, charset);
                String after = before;
                for (Replacement r : replacements) {
                    after = r.find().matcher(after).replaceAll(r.replace());
                }
                if (!after.equals(before)) {
                    Files.writeString(path, after, charset);
                    stepFiles++;
                }
            }
            changedFiles += stepFiles;
            details.add(step.rule().id() + ": " + stepFiles + " file(s) changed");
        }
        return new StageResult("replace", StageResult.Status.APPLIED,
                changedFiles + " file(s) changed by " + steps.size() + " text rule(s)", details);
    }

    private record Replacement(Pattern find, String replace) {
    }

    private static List<Replacement> replacements(String ruleId, FixSpec fix) {
        List<Replacement> result = new ArrayList<>();
        if (fix.find() != null) {
            result.add(replacement(fix, fix.find(), fix.replace()));
        }
        for (Map<String, Object> pair : fix.params(ruleId).maps("replacements")) {
            result.add(replacement(fix, String.valueOf(pair.get("find")), String.valueOf(pair.get("replace"))));
        }
        return result;
    }

    private static Replacement replacement(FixSpec fix, String find, String replace) {
        return fix.regex() ? new Replacement(Pattern.compile(find), replace)
                : new Replacement(Pattern.compile(Pattern.quote(find)), Matcher.quoteReplacement(replace));
    }

    private static Charset charsetOf(Path path) throws IOException {
        try {
            Files.readString(path, StandardCharsets.UTF_8);
            return StandardCharsets.UTF_8;
        } catch (CharacterCodingException e) {
            return StandardCharsets.ISO_8859_1;
        }
    }
}
