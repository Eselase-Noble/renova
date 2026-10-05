package io.renova.core.report;

import io.renova.core.engine.AnalysisResult;
import io.renova.core.engine.BuildError;
import io.renova.core.engine.MigrationOutcome;
import io.renova.core.engine.MigrationPlan;
import io.renova.core.engine.PlanStep;
import io.renova.core.engine.StageResult;
import io.renova.core.model.Category;
import io.renova.core.model.Finding;
import io.renova.core.model.Module;
import io.renova.core.playbook.FixSpec;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Human-readable assessment and migration report, structured like a hand-written migration guide. */
public final class MarkdownReport {

    private static final int EXAMPLES_PER_STEP = 5;

    private MarkdownReport() {
    }

    public static String render(AnalysisResult analysis, MigrationPlan plan, MigrationOutcome outcome) {
        StringBuilder md = new StringBuilder();
        md.append("# Migration assessment: ").append(analysis.project().root().getFileName()).append("\n\n");
        md.append("**Playbook:** ").append(plan.playbook().name()).append(" (`").append(plan.playbook().id())
                .append("`)  \n");
        plan.playbook().targets().forEach((k, v) -> md.append("**Target ").append(k).append(":** ").append(v).append("  \n"));
        md.append('\n');

        summary(md, analysis, plan);
        project(md, analysis);
        steps(md, analysis, plan);
        if (outcome != null) {
            migration(md, outcome);
        }
        if (!analysis.warnings().isEmpty()) {
            md.append("## Analysis warnings\n\n");
            analysis.warnings().forEach(w -> md.append("- ").append(w).append('\n'));
            md.append('\n');
        }
        return md.toString();
    }

    private static void summary(StringBuilder md, AnalysisResult analysis, MigrationPlan plan) {
        md.append("## Summary\n\n");
        md.append("| | |\n|---|---|\n");
        md.append("| Findings | ").append(analysis.findings().size()).append(" |\n");
        md.append("| Plan steps | ").append(plan.steps().size()).append(" |\n");
        md.append("| Automated (recipe + replace) | ").append(percent(plan.automationRate())).append(" of findings |\n");
        plan.occurrencesByStrategy().forEach((strategy, n) ->
                md.append("| Strategy `").append(strategy).append("` | ").append(n).append(" finding(s) |\n"));
        long guards = plan.playbook().rules().stream().filter(io.renova.core.playbook.Rule::guard).count();
        if (guards > 0) {
            md.append("| Guard rules checked after migration | ").append(guards).append(" |\n");
        }
        md.append('\n');

        md.append("| Category | Meaning | Findings |\n|---|---|---|\n");
        Map<Category, Long> counts = analysis.countByCategory();
        for (Category c : Category.values()) {
            md.append("| ").append(c.code()).append(" | ").append(c.description()).append(" | ")
                    .append(counts.getOrDefault(c, 0L)).append(" |\n");
        }
        md.append('\n');
    }

    private static void project(StringBuilder md, AnalysisResult analysis) {
        md.append("## Project\n\n");
        analysis.project().facts().forEach((k, v) -> md.append("- **").append(k).append(":** ").append(v).append('\n'));
        md.append('\n');
        List<Module> modules = analysis.project().modules();
        if (!modules.isEmpty()) {
            md.append("| Module | Path | Facts |\n|---|---|---|\n");
            for (Module m : modules) {
                String facts = m.facts().entrySet().stream()
                        .filter(e -> !(e.getValue() instanceof List<?>))
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .sorted()
                        .collect(Collectors.joining(", "));
                md.append("| ").append(m.name()).append(" | `").append(m.path()).append("` | ").append(facts).append(" |\n");
            }
            md.append('\n');
        }
    }

    private static void steps(StringBuilder md, AnalysisResult analysis, MigrationPlan plan) {
        md.append("## Plan\n\n");
        if (plan.steps().isEmpty()) {
            md.append("No rule of this playbook matched; nothing to migrate.\n\n");
            return;
        }
        Map<String, List<Finding>> byRule = analysis.findings().stream()
                .collect(Collectors.groupingBy(Finding::ruleId));
        for (PlanStep step : plan.steps()) {
            var rule = step.rule();
            md.append("### Step ").append(step.order()).append(": ").append(rule.title())
                    .append(" (category ").append(rule.category().code()).append(")\n\n");
            md.append("- **Rule:** `").append(rule.id()).append("`, severity ")
                    .append(rule.severity().name().toLowerCase(Locale.ROOT)).append('\n');
            md.append("- **Occurrences:** ").append(step.occurrences()).append(" in ").append(step.files().size())
                    .append(" file(s)\n");
            md.append("- **Fix:** ").append(describe(rule.fix())).append('\n');
            if (rule.rationale() != null) {
                md.append("- **Why:** ").append(rule.rationale().strip()).append('\n');
            }
            if (rule.fix().hint() != null) {
                md.append("- **Guidance:** ").append(rule.fix().hint().strip()).append('\n');
            }
            md.append("\n<details><summary>Examples</summary>\n\n");
            byRule.get(rule.id()).stream().limit(EXAMPLES_PER_STEP).forEach(f -> {
                md.append("- `").append(f.file()).append(f.line() > 0 ? ":" + f.line() : "").append('`');
                if (f.evidence() != null && !f.evidence().isEmpty()) {
                    md.append(": `").append(truncate(f.evidence(), 120).replace("`", "'")).append('`');
                }
                md.append('\n');
            });
            if (step.occurrences() > EXAMPLES_PER_STEP) {
                md.append("- … and ").append(step.occurrences() - EXAMPLES_PER_STEP).append(" more\n");
            }
            md.append("\n</details>\n\n");
        }
    }

    private static void migration(StringBuilder md, MigrationOutcome outcome) {
        md.append("## Migration run\n\n");
        md.append("Workspace: `").append(outcome.workspace()).append("` (each stage is a git commit)\n\n");
        md.append("| Stage | Status | Summary |\n|---|---|---|\n");
        for (StageResult s : outcome.stages()) {
            md.append("| ").append(s.stage()).append(" | ").append(s.status()).append(" | ")
                    .append(s.summary().replace("|", "\\|")).append(" |\n");
        }
        md.append('\n');
        for (StageResult s : outcome.stages()) {
            if (!s.details().isEmpty()) {
                md.append("**").append(s.stage()).append(" details**\n\n```\n");
                s.details().forEach(d -> md.append(d).append('\n'));
                md.append("```\n\n");
            }
        }
        if (outcome.verification() != null) {
            var v = outcome.verification();
            md.append("### Verification: ").append(v.success() ? "build passes" : "build fails").append("\n\n");
            if (!v.errors().isEmpty()) {
                md.append("| File | Line | Error |\n|---|---|---|\n");
                for (BuildError e : v.errors().stream().limit(50).toList()) {
                    md.append("| `").append(e.file()).append("` | ").append(e.line()).append(" | ")
                            .append(truncate(e.message(), 160).replace("|", "\\|")).append(" |\n");
                }
                md.append('\n');
            }
        }
        if (outcome.behaviour() != null) {
            var b = outcome.behaviour();
            md.append("### Behaviour: ").append(b.status().name().toLowerCase(java.util.Locale.ROOT)).append("\n\n")
                    .append(b.summary()).append(". Details: `.renova/behaviour.md`.\n\n");
            b.results().stream().filter(r -> !r.same()).limit(20).forEach(r -> md.append("- `").append(r.scenario().method())
                    .append(' ').append(r.scenario().path()).append("`: ").append(String.join("; ", r.differences())).append('\n'));
            if (b.differing() > 0) {
                md.append('\n');
            }
        }
        if (!outcome.manualSteps().isEmpty()) {
            md.append("### Manual follow-up\n\n");
            outcome.manualSteps().forEach(s -> md.append("- [ ] Step ").append(s.order()).append(": ")
                    .append(s.rule().title()).append(" (").append(s.files().size()).append(" file(s))\n"));
            md.append('\n');
        }
    }

    private static String describe(FixSpec fix) {
        return switch (fix.strategy()) {
            case FixSpec.RECIPE -> "automated recipe " + fix.recipes().stream().map(r -> "`" + r + "`").collect(Collectors.joining(", "));
            case FixSpec.REPLACE -> "automated text replacement `" + fix.find() + "` → `" + fix.replace() + "` in `" + fix.include() + "`";
            case FixSpec.AI -> "AI-assisted edit, checked by the build";
            case FixSpec.MANUAL -> "manual";
            default -> "strategy `" + fix.strategy() + "`";
        };
    }

    private static String percent(double rate) {
        return Math.round(rate * 100) + "%";
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
