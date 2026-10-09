package io.renova.dotnet.fix;

import io.renova.core.engine.MigrationContext;
import io.renova.core.engine.PlanStep;
import io.renova.core.engine.StageResult;
import io.renova.core.spi.Fixer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Fix strategy {@code dotnet-source}: rewrites of C# source that follow from the text alone, driven by the
 * rule's {@code fix.params}. {@code action: nunit3} moves tests written for NUnit 2 to NUnit 3 (see
 * {@link NUnit3}); {@code action: efcore} gives Entity Framework 6 code the names Entity Framework Core has
 * for the same things (see {@link EfCore}).
 */
public final class DotnetSourceFixer implements Fixer {

    public static final String STRATEGY = "dotnet-source";

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
            String action = step.rule().fix().params(step.rule().id()).string("action");
            if (!action.equals("nunit3") && !action.equals("efcore")) {
                throw new IllegalArgumentException("Rule '" + step.rule().id() + "': unknown dotnet-source action '" + action
                        + "'; use nunit3 or efcore");
            }
            for (String file : step.files()) {
                if (!file.endsWith(".cs")) {
                    details.add(step.rule().id() + ": " + file + ": left as it is (only C# is rewritten)");
                    continue;
                }
                Path path = root.resolve(file);
                String before = Files.readString(path, StandardCharsets.UTF_8);
                String after = action.equals("efcore") ? EfCore.rename(before) : NUnit3.upgrade(before);
                if (!after.equals(before)) {
                    Files.writeString(path, after, StandardCharsets.UTF_8);
                    changed++;
                }
                details.add(step.rule().id() + ": " + file + ": " + (after.equals(before) ? "nothing to rewrite" : action));
            }
        }
        return new StageResult(STRATEGY, StageResult.Status.APPLIED, changed + " source file(s) rewritten by " + steps.size() + " rule(s)", details);
    }
}
