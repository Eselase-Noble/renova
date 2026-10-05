package io.renova.core.ai;

import io.renova.core.engine.BuildError;
import io.renova.core.engine.MigrationContext;
import io.renova.core.engine.PlanStep;
import io.renova.core.engine.StageResult;
import io.renova.core.engine.VerifyResult;
import io.renova.core.playbook.FixSpec;
import io.renova.core.spi.Fixer;
import io.renova.core.spi.Verifier;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Handles the long tail that deterministic rules cannot: proactive edits for rules with strategy
 * {@code ai}, and a build-repair loop that feeds build errors back to the model until the build
 * passes or the iteration budget runs out. Every round of edits is committed separately.
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
        Tally tally = new Tally();
        for (Map.Entry<String, List<String>> entry : hintsByFile.entrySet()) {
            edit(context, entry.getKey(), entry.getValue(), List.of(), tally);
        }
        context.workspace().commitAll("renova: AI-assisted rule fixes (" + tally.changed + " files)");
        int handled = tally.changed + tally.unchanged;
        StageResult.Status status = handled == hintsByFile.size() ? StageResult.Status.APPLIED
                : handled == 0 ? StageResult.Status.FAILED : StageResult.Status.PARTIAL;
        return new StageResult("ai", status, tally.changed + " of " + hintsByFile.size() + " file(s) edited by "
                + describe(ai) + "; " + tally.usage(), tally.log);
    }

    /** Re-runs the build after each round of fixes until it passes or {@code maxIterations} is spent. */
    public VerifyResult repair(MigrationContext context, Verifier verifier, VerifyResult failed,
                               int maxIterations, List<String> log) throws Exception {
        VerifyResult current = failed;
        Tally tally = new Tally();
        for (int round = 1; round <= maxIterations && !current.success() && context.ai().available(); round++) {
            Map<String, List<BuildError>> byFile = current.errors().stream()
                    .filter(e -> e.file() != null)
                    .collect(Collectors.groupingBy(BuildError::file, LinkedHashMap::new, Collectors.toList()));
            if (byFile.isEmpty()) {
                log.add("round " + round + ": build failed without file-level errors; stopping");
                break;
            }
            int before = tally.changed;
            for (Map.Entry<String, List<BuildError>> entry : byFile.entrySet()) {
                edit(context, entry.getKey(), List.of(), entry.getValue(), tally);
            }
            log.addAll(tally.log);
            tally.log.clear();
            if (tally.changed == before) {
                log.add("round " + round + ": no edits proposed; stopping");
                break;
            }
            context.workspace().commitAll("renova: AI build repair, round " + round);
            current = verifier.verify(context);
            log.add("round " + round + ": " + (tally.changed - before) + " file(s) edited, build "
                    + (current.success() ? "passes" : "still fails"));
        }
        log.add("usage: " + tally.usage());
        return current;
    }

    private static void edit(MigrationContext context, String file, List<String> hints, List<BuildError> errors,
                             Tally tally) throws Exception {
        Path path = context.workspace().root().resolve(file);
        if (!Files.isRegularFile(path)) {
            tally.log.add(file + ": skipped (not a file)");
            return;
        }
        String content;
        try {
            content = Files.readString(path, StandardCharsets.UTF_8);
        } catch (CharacterCodingException e) {
            tally.log.add(file + ": skipped (not UTF-8; convert the file encoding first)");
            return;
        }
        Proposal proposal;
        try {
            proposal = context.ai().propose(new FixRequest(context.playbook().name(), file, content, hints, errors));
        } catch (AiProviderException e) {
            if (e.fatal()) {
                throw e;
            }
            tally.log.add(file + ": provider error: " + e.getMessage());
            return;
        }
        tally.inputTokens += proposal.inputTokens();
        tally.outputTokens += proposal.outputTokens();
        switch (proposal.outcome()) {
            case CHANGED -> {
                if (proposal.newContent().equals(content)) {
                    tally.unchanged++;
                    tally.log.add(file + ": unchanged");
                } else {
                    Files.writeString(path, proposal.newContent(), StandardCharsets.UTF_8);
                    tally.changed++;
                    tally.log.add(file + ": edited: " + proposal.rationale());
                }
            }
            case UNCHANGED -> {
                tally.unchanged++;
                tally.log.add(file + ": no change needed: " + proposal.rationale());
            }
            case DECLINED -> tally.log.add(file + ": declined: " + proposal.rationale());
        }
    }

    private static String hint(PlanStep step) {
        String hint = step.rule().fix().hint();
        return hint == null ? step.rule().title() : step.rule().title() + ": " + hint.strip();
    }

    private static String describe(AiProvider ai) {
        return ai.model() == null ? ai.name() : ai.name() + " (" + ai.model() + ")";
    }

    private static final class Tally {
        int changed;
        int unchanged;
        long inputTokens;
        long outputTokens;
        final List<String> log = new ArrayList<>();

        String usage() {
            return inputTokens + " input / " + outputTokens + " output tokens";
        }
    }
}
