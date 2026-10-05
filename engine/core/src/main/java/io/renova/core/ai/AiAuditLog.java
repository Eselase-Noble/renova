package io.renova.core.ai;

import io.renova.core.engine.BuildError;
import io.renova.core.engine.MigrationContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Keeps a record of every AI exchange in the workspace ({@code .renova/ai/NNN.md}): what was asked,
 * which files were offered and in which role, the outcome, token usage, and the raw answer. File
 * contents are not copied; they are in the workspace's git history. The directory is excluded from
 * version control.
 */
final class AiAuditLog {

    private AiAuditLog() {
    }

    static void record(MigrationContext context, FixRequest request, Proposal proposal) {
        try {
            Path dir = Files.createDirectories(context.workspace().lcDir().resolve("ai"));
            long next;
            try (Stream<Path> existing = Files.list(dir)) {
                next = existing.count() + 1;
            }
            StringBuilder md = new StringBuilder();
            md.append("# AI exchange ").append(next).append("\n\n");
            md.append("- **Provider:** ").append(context.ai().name())
                    .append(context.ai().model() == null ? "" : " / " + context.ai().model()).append('\n');
            md.append("- **Goal:** ").append(request.goal()).append('\n');
            md.append("- **Outcome:** ").append(proposal.outcome()).append(" (").append(proposal.inputTokens())
                    .append(" input / ").append(proposal.outputTokens()).append(" output tokens)\n");
            md.append("- **Rationale:** ").append(proposal.rationale()).append("\n\n");
            md.append("## Files offered\n\n");
            request.files().forEach(f -> md.append("- `").append(f.path()).append("` (").append(f.role().name().toLowerCase())
                    .append(f.why() == null ? "" : ": " + f.why()).append(")\n"));
            if (!request.hints().isEmpty()) {
                md.append("\n## Rules\n\n");
                request.hints().forEach(h -> md.append("- ").append(h).append('\n'));
            }
            if (!request.errors().isEmpty()) {
                md.append("\n## Errors\n\n");
                for (BuildError e : request.errors()) {
                    md.append("- `").append(e.file()).append(e.line() > 0 ? ":" + e.line() : "").append("`: ")
                            .append(e.message()).append('\n');
                }
            }
            if (!proposal.edits().isEmpty()) {
                md.append("\n## Files changed\n\n");
                proposal.edits().keySet().forEach(p -> md.append("- `").append(p).append("`\n"));
            }
            if (proposal.rawResponse() != null) {
                md.append("\n## Raw response\n\n```json\n").append(proposal.rawResponse()).append("\n```\n");
            }
            Files.writeString(dir.resolve(String.format("%03d.md", next)), md.toString());
        } catch (IOException e) {
            // The audit log must never stop a migration.
        }
    }
}
