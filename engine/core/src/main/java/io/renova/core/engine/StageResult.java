package io.renova.core.engine;

import java.util.List;

public record StageResult(String stage, Status status, String summary, List<String> details) {

    public enum Status { APPLIED, PARTIAL, SKIPPED, FAILED }

    public StageResult {
        details = List.copyOf(details);
    }

    public static StageResult skipped(String stage, String summary) {
        return new StageResult(stage, Status.SKIPPED, summary, List.of());
    }
}
