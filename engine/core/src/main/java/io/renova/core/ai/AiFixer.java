package io.renova.core.ai;

import io.renova.core.engine.BuildError;
import io.renova.core.engine.MigrationContext;
import io.renova.core.engine.PlanStep;
import io.renova.core.engine.StageResult;
import io.renova.core.engine.VerifyResult;
import io.renova.core.playbook.FixSpec;
import io.renova.core.playbook.Params;
import io.renova.core.scan.ScanContext;
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
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Handles the long tail that deterministic rules cannot: proactive edits for rules with strategy
 * {@code ai}, and a build-repair loop that feeds build errors back to the model until the build
 * passes or the iteration budget runs out.
 *
 * <p>Each request carries the target files plus their related files from the ecosystem plugin
 * (for Java, the owning build file), so a fix that spans a source file and its build file is one
 * edit. The model may only change files it was given as editable; anything else is rejected.
 * With RAG enabled, retrieved code is added as reference files and retrieved knowledge as notes,
 * within a share of the request size. Test files are sent as reference: a migration must keep the
 * behaviour they describe, not change them to pass. The one exception is a test that an earlier stage
 * rewrote and left uncompilable: it describes nothing until it compiles, so its compiler errors may be
 * repaired.
 *
 * <p>A rule whose fix has {@code params.together} replaces something no single file holds, such as a web
 * framework: all its files go in one request, with the files named by {@code params.with} (pages,
 * descriptors) editable beside them, and new files allowed where {@code params.create} says. Where the
 * other files cannot be told by name, {@code params.withContaining} keeps those that match a pattern. Where
 * an earlier stage moves the files the rule found, {@code params.files} names them as they are when AI runs.
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
            return StageResult.skipped("ai", steps.size() + " step(s) need an AI provider or a person");
        }
        // One request per file, carrying every rule that matched it; a rule that changes a whole layer
        // goes first, in one request for all its files.
        Map<String, List<String>> hintsByFile = new LinkedHashMap<>();
        Tally tally = new Tally();
        ScanContext scan = null;
        for (PlanStep step : steps) {
            Params params = step.rule().fix().params(step.rule().id());
            if (!params.optString("together").map(Boolean::parseBoolean).orElse(false)) {
                continue;
            }
            // A rule whose files an earlier stage moved or made names them as they are now (params.files).
            List<String> now = params.strings("files");
            if (!now.isEmpty()) {
                scan = ScanContext.of(context.plugin().model(context.workspace().root()));
            }
            scan = scan == null ? ScanContext.of(context.project()) : scan;
            List<String> targets = now.isEmpty() ? step.files() : scan.files(now).stream().map(ScanContext::toProjectPath)
                    .filter(f -> !context.plugin().isTestFile(f)).toList();
            if (targets.isEmpty()) {
                continue;
            }
            // Of the files the globs name, optionally only those that hold the thing being replaced.
            Optional<Pattern> holding = params.optString("withContaining").map(Pattern::compile);
            ScanContext scanned = scan;
            List<String> companions = scan.files(params.strings("with")).stream()
                    .filter(f -> holding.isEmpty() || holding.get().matcher(String.join("\n", scanned.lines(f))).find())
                    .map(ScanContext::toProjectPath).filter(f -> !context.plugin().isTestFile(f)).toList();
            targets.forEach(f -> hintsByFile.put(f, new ArrayList<>()));
            send(context, targets, List.of(hint(step)), List.of(), tally,
                    new Layer(companions, step.rule().title(), params.strings("create")));
        }
        for (PlanStep step : steps) {
            boolean together = step.rule().fix().params(step.rule().id()).optString("together")
                    .map(Boolean::parseBoolean).orElse(false);
            for (String file : together ? List.<String>of() : step.files()) {
                hintsByFile.computeIfAbsent(file, f -> new ArrayList<>()).add(hint(step));
            }
        }
        for (Map.Entry<String, List<String>> entry : hintsByFile.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                send(context, List.of(entry.getKey()), entry.getValue(), List.of(), tally);
            }
        }
        context.workspace().commitAll("renova: AI-assisted rule fixes (" + tally.filesChanged.size() + " files)");
        int handled = tally.requestsChanged + tally.requestsUnchanged;
        StageResult.Status status = handled == tally.requests ? StageResult.Status.APPLIED
                : handled == 0 ? StageResult.Status.FAILED : StageResult.Status.PARTIAL;
        return new StageResult("ai", status, tally.filesChanged.size() + " file(s) edited for " + hintsByFile.size()
                + " flagged file(s) by " + describe(ai) + "; " + tally.usage(), tally.log);
    }

    /** Called after a repair round's edits are committed and before the build runs again. */
    @FunctionalInterface
    public interface RoundHook {
        void afterEdits(int round) throws Exception;
    }

    /** Re-runs the build after each round of fixes until it passes or {@code maxIterations} is spent. */
    public VerifyResult repair(MigrationContext context, Verifier verifier, VerifyResult failed,
                               int maxIterations, List<String> log) throws Exception {
        return repair(context, verifier, failed, maxIterations, log, round -> { });
    }

    /**
     * As {@link #repair(MigrationContext, Verifier, VerifyResult, int, List)}, running {@code afterEdits}
     * on each round's edits before rebuilding, so the engine can check them (for example with guards).
     */
    public VerifyResult repair(MigrationContext context, Verifier verifier, VerifyResult failed,
                               int maxIterations, List<String> log, RoundHook afterEdits) throws Exception {
        return repair(context, verifier, failed, maxIterations, log, afterEdits, "build");
    }

    /** @param kind what is repaired, for commit messages: "build" or "behaviour" */
    public VerifyResult repair(MigrationContext context, Verifier verifier, VerifyResult failed,
                               int maxIterations, List<String> log, RoundHook afterEdits, String kind) throws Exception {
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
            context.workspace().commitAll("renova: AI " + kind + " repair, round " + round);
            afterEdits.afterEdits(round);
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

    /**
     * What a whole-layer request adds to its targets.
     *
     * @param companions files that change with the targets, editable
     * @param what       the rule's title, to say why they are there
     * @param creatable  globs of paths where new files may be added
     */
    private record Layer(List<String> companions, String what, List<String> creatable) {
        static final Layer NONE = new Layer(List.of(), null, List.of());
    }

    private static void send(MigrationContext context, List<String> targets, List<String> hints,
                             List<BuildError> errors, Tally tally) throws Exception {
        send(context, targets, hints, errors, tally, Layer.NONE);
    }

    private static void send(MigrationContext context, List<String> targets, List<String> hints,
                             List<BuildError> errors, Tally tally, Layer layer) throws Exception {
        List<RequestFile> files = new ArrayList<>();
        Set<String> included = new LinkedHashSet<>();
        for (String target : targets) {
            String content = read(context, target, tally);
            if (content != null && included.add(target)) {
                if (!context.plugin().isTestFile(target)) {
                    files.add(new RequestFile(target, content, RequestFile.Role.TARGET, null));
                } else if (leftUncompilable(context, target, errors)) {
                    files.add(new RequestFile(target, content, RequestFile.Role.TARGET,
                            "a test the migration rewrote and left uncompilable; repair only what the compiler "
                                    + "reports, and keep every call, value and assertion it had"));
                } else {
                    files.add(new RequestFile(target, content, RequestFile.Role.REFERENCE,
                            "the failing test; tests define the expected behaviour and are never changed"));
                }
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
        for (String companion : layer.companions()) {
            String content = included.contains(companion) ? null : read(context, companion, null);
            if (content != null) {
                included.add(companion);
                files.add(new RequestFile(companion, content, RequestFile.Role.RELATED, "changes with the targets: " + layer.what()));
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
        FixRequest request = new FixRequest(context.playbook().name(), files, hints, errors, knowledge, layer.creatable());
        if (request.totalChars() > MAX_REQUEST_CHARS && targets.size() > 1 && layer != Layer.NONE) {
            // Too much for one response: half the targets at a time, each half with the layer's other files.
            send(context, targets.subList(0, targets.size() / 2), hints, errors, tally, layer);
            send(context, targets.subList(targets.size() / 2, targets.size()), hints, errors, tally, layer);
            return;
        }
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

    /**
     * A test whose errors all come from the compiler, in a file an earlier stage changed. A test that runs
     * and fails, or one the migration never touched, still defines the behaviour to keep.
     */
    private static boolean leftUncompilable(MigrationContext context, String test, List<BuildError> errors) throws Exception {
        List<BuildError> own = errors.stream().filter(e -> test.equals(e.file())).toList();
        return !own.isEmpty() && own.stream().noneMatch(BuildError::fromFailedTest)
                && context.workspace().changedSinceBaseline(test);
    }

    /**
     * Writes only edits to files the request offered as editable, and new files only where the request
     * allows them; always inside the workspace, and never a test.
     */
    private static void write(MigrationContext context, FixRequest request, Proposal proposal, String label,
                              Tally tally) throws IOException {
        Path root = context.workspace().root();
        Set<String> editable = request.editablePaths();
        List<String> written = new ArrayList<>();
        for (Map.Entry<String, String> edit : proposal.edits().entrySet()) {
            String path = edit.getKey();
            Path target = root.resolve(path).normalize();
            boolean added = !Files.exists(target) && target.startsWith(root) && !context.plugin().isTestFile(path)
                    && request.creatable().stream().anyMatch(glob -> ScanContext.matches(glob, root.relativize(target)));
            if (!(editable.contains(path) || added) || !target.startsWith(root)) {
                tally.log.add(label + ": rejected edit to " + path + " (not offered as editable)");
                continue;
            }
            if (added) {
                Files.createDirectories(target.getParent());
            }
            String before = added ? null : Files.readString(target, StandardCharsets.UTF_8);
            if (!edit.getValue().equals(before)) {
                Files.writeString(target, edit.getValue(), StandardCharsets.UTF_8);
                written.add(path);
                tally.filesChanged.add(path);
                tally.writtenThisRound.add(path);
            }
        }
        // Only a whole-layer request may remove files, and only ones it was given to change.
        for (String path : proposal.deletes()) {
            Path target = root.resolve(path).normalize();
            if (request.creatable().isEmpty() || !editable.contains(path) || !target.startsWith(root)
                    || context.plugin().isTestFile(path)) {
                tally.log.add(label + ": rejected removal of " + path + " (not offered as removable)");
            } else if (Files.deleteIfExists(target)) {
                written.add(path + " (removed)");
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
