package io.renova.core.ai;

import io.renova.core.engine.BuildError;
import io.renova.core.engine.MigrationContext;
import io.renova.core.engine.PlanStep;
import io.renova.core.engine.StageResult;
import io.renova.core.engine.VerifyResult;
import io.renova.core.playbook.FixSpec;
import io.renova.core.spi.Fixer;
import io.renova.core.spi.Verifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Handles the long tail that deterministic rules cannot: proactive edits for rules with strategy
 * {@code ai}, and a build-repair loop that feeds compiler errors back to the model until the build
 * passes or the iteration budget runs out. Every accepted edit is committed separately.
 */
public final class AiFixer implements Fixer {

    @Override
    public String strategy() {
        return FixSpec.AI;
    }

    @Override
    public StageResult apply(MigrationContext context, List<PlanStep> steps) throws Exception {
        AiProvider ai = context.ai();
        if (!ai.available()) {
            return StageResult.skipped("ai", steps.size() + " step(s) need an AI provider (--ai) or manual work");
        }
        // One request per file, carrying every rule that matched it.
        Map<String, List<String>> hintsByFile = new LinkedHashMap<>();
        for (PlanStep step : steps) {
            for (String file : step.files()) {
                hintsByFile.computeIfAbsent(file, f -> new ArrayList<>()).add(hint(step));
            }
        }
        List<String> details = new ArrayList<>();
        int changed = 0;
        for (Map.Entry<String, List<String>> entry : hintsByFile.entrySet()) {
            if (edit(context, entry.getKey(), entry.getValue(), List.of(), details)) {
                changed++;
            }
        }
        context.workspace().commitAll("renova: AI-assisted rule fixes (" + changed + " files)");
        StageResult.Status status = changed == hintsByFile.size() ? StageResult.Status.APPLIED : StageResult.Status.PARTIAL;
        return new StageResult("ai", status, changed + " of " + hintsByFile.size() + " file(s) edited by " + ai.name(), details);
    }

    /** Re-runs the build after each round of fixes until it passes or {@code maxIterations} is spent. */
    public VerifyResult repair(MigrationContext context, Verifier verifier, VerifyResult failed,
                               int maxIterations, List<String> log) throws Exception {
        VerifyResult current = failed;
        for (int round = 1; round <= maxIterations && !current.success() && context.ai().available(); round++) {
            Map<String, List<BuildError>> byFile = current.errors().stream()
                    .filter(e -> e.file() != null)
                    .collect(Collectors.groupingBy(BuildError::file, LinkedHashMap::new, Collectors.toList()));
            if (byFile.isEmpty()) {
                log.add("round " + round + ": build failed without file-level errors; stopping");
                break;
            }
            int changed = 0;
            for (Map.Entry<String, List<BuildError>> entry : byFile.entrySet()) {
                if (edit(context, entry.getKey(), List.of(), entry.getValue(), log)) {
                    changed++;
                }
            }
            if (changed == 0) {
                log.add("round " + round + ": no edits proposed; stopping");
                break;
            }
            context.workspace().commitAll("renova: AI build repair, round " + round);
            current = verifier.verify(context);
            log.add("round " + round + ": " + changed + " file(s) edited, build " + (current.success() ? "passes" : "still fails"));
        }
        return current;
    }

    private static boolean edit(MigrationContext context, String file, List<String> hints, List<BuildError> errors,
                                List<String> log) throws Exception {
        Path path = context.workspace().root().resolve(file);
        if (!Files.isRegularFile(path)) {
            return false;
        }
        String content = Files.readString(path);
        FixRequest request = new FixRequest(context.playbook().name(), file, content, hints, errors);
        Optional<FilePatch> patch = context.ai().propose(request);
        if (patch.isEmpty() || patch.get().newContent().equals(content)) {
            return false;
        }
        Files.writeString(path, patch.get().newContent());
        log.add(file + ": " + Objects.requireNonNullElse(patch.get().rationale(), "edited"));
        return true;
    }

    private static String hint(PlanStep step) {
        String hint = step.rule().fix().hint();
        return hint == null ? step.rule().title() : step.rule().title() + ": " + hint;
    }
}
