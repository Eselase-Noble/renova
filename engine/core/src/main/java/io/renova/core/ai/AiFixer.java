package io.renova.core.ai;

import io.renova.core.engine.BuildError;
import io.renova.core.engine.MigrationContext;
import io.renova.core.engine.PlanStep;
import io.renova.core.engine.StageResult;
import io.renova.core.engine.VerifyResult;
import io.renova.core.playbook.FixSpec;
import io.renova.core.rag.ContextAssembler;
import io.renova.core.rag.ContextItem;
import io.renova.core.rag.RetrievalQuery;
import io.renova.core.spi.Fixer;
import io.renova.core.spi.RelatedFile;
import io.renova.core.spi.Verifier;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Handles the long tail that deterministic rules cannot: proactive edits for rules with strategy
 * {@code ai}, and a build-repair loop that feeds build errors back to the model until the build
 * passes or the iteration budget runs out.
 *
 * <p>Each request carries the target files plus their related files from the ecosystem plugin
 * (for Java, the owning build file), so a fix that spans a source file and its build file is one
 * edit. The model may only change files it was given as editable; anything else is rejected.
 * With RAG enabled, retrieved code is added as reference files and retrieved knowledge as notes,
 * within a share of the request size.
 */
public final class AiFixer implements Fixer {

    /** Keep requests well inside what one response can return in full. */
    static final int MAX_REQUEST_CHARS = 150_000;

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
            send(context, List.of(entry.getKey()), entry.getValue(), List.of(), tally);
        }
        context.workspace().commitAll("renova: AI-assisted rule fixes (" + tally.filesChanged.size() + " files)");
        int handled = tally.requestsChanged + tally.requestsUnchanged;
        StageResult.Status status = handled == tally.requests ? StageResult.Status.APPLIED
                : handled == 0 ? StageResult.Status.FAILED : StageResult.Status.PARTIAL;
        return new StageResult("ai", status, tally.filesChanged.size() + " file(s) edited for " + hintsByFile.size()
                + " flagged file(s) by " + describe(ai) + "; " + tally.usage(), tally.log);
    }

    /** Re-runs the build after each round of fixes until it passes or {@code maxIterations} is spent. */
    public VerifyResult repair(MigrationContext context, Verifier verifier, VerifyResult failed,
                               int maxIterations, List<String> log) throws Exception {
        VerifyResult current = failed;
        Tally tally = new Tally();
        for (int round = 1; round <= maxIterations && !current.success() && context.ai().available(); round++) {
            List<Group> groups = groupByRelatedFiles(context, current.errors());
            if (groups.isEmpty()) {
                log.add("round " + round + ": build failed without file-level errors; stopping");
                break;
            }
            tally.writtenThisRound.clear();
            for (Group group : groups) {
                send(context, group.targets, List.of(), group.errors, tally);
            }
            log.addAll(tally.log);
            tally.log.clear();
            // Counted per round: the same file (often the build file) may need edits in several rounds.
            Set<String> changedThisRound = new LinkedHashSet<>(tally.writtenThisRound);
            if (changedThisRound.isEmpty()) {
                log.add("round " + round + ": no edits proposed; stopping");
                break;
            }
            context.workspace().commitAll("renova: AI build repair, round " + round);
            current = verifier.verify(context);
            log.add("round " + round + ": edited " + String.join(", ", changedThisRound) + "; build "
                    + (current.success() ? "passes" : "still fails"));
        }
        log.add("usage: " + tally.usage());
        return current;
    }

    /**
     * Groups failing files that share the same editable related files (typically the same module
     * build file), so the model sees every error a shared build change could fix at once.
     */
    private static List<Group> groupByRelatedFiles(MigrationContext context, List<BuildError> errors) {
        Map<String, Group> groups = new LinkedHashMap<>();
        for (BuildError error : errors) {
            if (error.file() == null) {
                continue;
            }
            Set<String> key = new TreeSet<>();
            context.plugin().relatedFiles(context.project(), error.file()).stream()
                    .filter(RelatedFile::editable).map(RelatedFile::path).forEach(key::add);
            Group group = groups.computeIfAbsent(String.join("|", key), k -> new Group());
            if (!group.targets.contains(error.file())) {
                group.targets.add(error.file());
            }
            group.errors.add(error);
        }
        return List.copyOf(groups.values());
    }

    private static void send(MigrationContext context, List<String> targets, List<String> hints,
                             List<BuildError> errors, Tally tally) throws Exception {
        List<RequestFile> files = new ArrayList<>();
        Set<String> included = new LinkedHashSet<>();
        for (String target : targets) {
            String content = read(context, target, tally);
            if (content != null && included.add(target)) {
                files.add(new RequestFile(target, content, RequestFile.Role.TARGET, null));
            }
        }
        if (files.isEmpty()) {
            return;
        }
        for (String target : targets) {
            for (RelatedFile related : context.plugin().relatedFiles(context.project(), target)) {
                if (included.contains(related.path())) {
                    continue;
                }
                String content = read(context, related.path(), null);
                if (content != null) {
                    included.add(related.path());
                    files.add(new RequestFile(related.path(), content,
                            related.editable() ? RequestFile.Role.RELATED : RequestFile.Role.REFERENCE, related.why()));
                }
            }
        }

        List<ContextItem> knowledge = new ArrayList<>();
        ContextAssembler assembler = ContextAssembler.forMigration(context, MAX_REQUEST_CHARS);
        if (assembler != null) {
            Map<String, String> targetContents = new LinkedHashMap<>();
            files.stream().filter(f -> f.role() == RequestFile.Role.TARGET).forEach(f -> targetContents.put(f.path(), f.content()));
            int used = files.stream().mapToInt(f -> f.content().length()).sum();
            for (ContextItem item : assembler.assemble(new RetrievalQuery(targetContents, hints, errors), included,
                    MAX_REQUEST_CHARS - used)) {
                if (item.kind() == ContextItem.Kind.CODE) {
                    included.add(item.source());
                    files.add(new RequestFile(item.source(), item.content(), RequestFile.Role.REFERENCE, item.why()));
                } else {
                    knowledge.add(item);
                }
                tally.retrieved++;
            }
        }

        String label = String.join(", ", targets);
        FixRequest request = new FixRequest(context.playbook().name(), files, hints, errors, knowledge);
        if (request.totalChars() > MAX_REQUEST_CHARS && targets.size() > 1) {
            // Too much for one response: fall back to one target per request.
            for (String target : targets) {
                List<BuildError> own = errors.stream().filter(e -> target.equals(e.file())).toList();
                send(context, List.of(target), hints, own, tally);
            }
            return;
        }

        tally.requests++;
        Proposal proposal;
        try {
            proposal = context.ai().propose(request);
        } catch (AiProviderException e) {
            if (e.fatal()) {
                throw e;
            }
            tally.log.add(label + ": provider error: " + e.getMessage());
            return;
        }
        tally.inputTokens += proposal.inputTokens();
        tally.outputTokens += proposal.outputTokens();
        AiAuditLog.record(context, request, proposal);
        switch (proposal.outcome()) {
            case CHANGED -> write(context, request, proposal, label, tally);
            case UNCHANGED -> {
                tally.requestsUnchanged++;
                tally.log.add(label + ": no change needed: " + proposal.rationale());
            }
            case DECLINED -> tally.log.add(label + ": declined: " + proposal.rationale());
        }
    }

    /** Writes only edits to files the request offered as editable, and only inside the workspace. */
    private static void write(MigrationContext context, FixRequest request, Proposal proposal, String label,
                              Tally tally) throws IOException {
        Path root = context.workspace().root();
        Set<String> editable = request.editablePaths();
        List<String> written = new ArrayList<>();
        for (Map.Entry<String, String> edit : proposal.edits().entrySet()) {
            String path = edit.getKey();
            Path target = root.resolve(path).normalize();
            if (!editable.contains(path) || !target.startsWith(root)) {
                tally.log.add(label + ": rejected edit to " + path + " (not offered as editable)");
                continue;
            }
            String before = Files.readString(target, StandardCharsets.UTF_8);
            if (!before.equals(edit.getValue())) {
                Files.writeString(target, edit.getValue(), StandardCharsets.UTF_8);
                written.add(path);
                tally.filesChanged.add(path);
                tally.writtenThisRound.add(path);
            }
        }
        if (written.isEmpty()) {
            tally.requestsUnchanged++;
            tally.log.add(label + ": unchanged");
        } else {
            tally.requestsChanged++;
            tally.log.add(label + ": edited " + String.join(", ", written) + ": " + proposal.rationale());
        }
    }

    /** UTF-8 content, or null (logged when a tally is given) for missing or non-UTF-8 files. */
    private static String read(MigrationContext context, String file, Tally tally) throws IOException {
        Path path = context.workspace().root().resolve(file);
        if (!Files.isRegularFile(path)) {
            if (tally != null) {
                tally.log.add(file + ": skipped (not a file)");
            }
            return null;
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (CharacterCodingException e) {
            if (tally != null) {
                tally.log.add(file + ": skipped (not UTF-8; convert the file encoding first)");
            }
            return null;
        }
    }

    private static String hint(PlanStep step) {
        String hint = step.rule().fix().hint();
        return hint == null ? step.rule().title() : step.rule().title() + ": " + hint.strip();
    }

    private static String describe(AiProvider ai) {
        return ai.model() == null ? ai.name() : ai.name() + " (" + ai.model() + ")";
    }

    private static final class Group {
        final List<String> targets = new ArrayList<>();
        final List<BuildError> errors = new ArrayList<>();
    }

    private static final class Tally {
        int requests;
        int requestsChanged;
        int requestsUnchanged;
        final Set<String> filesChanged = new LinkedHashSet<>();
        final List<String> writtenThisRound = new ArrayList<>();
        long inputTokens;
        long outputTokens;
        int retrieved;
        final List<String> log = new ArrayList<>();

        String usage() {
            return inputTokens + " input / " + outputTokens + " output tokens"
                    + (retrieved == 0 ? "" : "; " + retrieved + " retrieved context item(s)");
        }
    }
}
