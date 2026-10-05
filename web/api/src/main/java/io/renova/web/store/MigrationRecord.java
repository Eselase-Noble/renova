package io.renova.web.store;

/**
 * One migration job and, once finished, its headline results. Details are in the workspace's reports.
 *
 * @param workspace directory of the migrated copy (a git repository with one commit per stage)
 * @param error          why the job could not complete; null otherwise
 * @param organisationId the organisation of the project
 * @param startedBy      id of the user who started it
 */
public record MigrationRecord(String id, String projectId, String projectName, String playbook, Options options,
                              Status status, String createdAt, String startedAt, String finishedAt, String workspace,
                              Summary summary, String error, String organisationId, String startedBy) {

    public enum Status { QUEUED, RUNNING, PASSED, FAILED, ERROR }

    /** What the user asked for. */
    public record Options(boolean ai, boolean rag, boolean verifyBehaviour, boolean skipTests, int maxAiIterations) {
    }

    /**
     * @param build     PASSES, FAILS or NOT_VERIFIED
     * @param behaviour SAME, DIFFERENT, SKIPPED or FAILED; null when not checked
     */
    public record Summary(String build, int buildErrors, String behaviour, String behaviourSummary, int repairRounds,
                          int aiRequests, long inputTokens, long outputTokens, int manualSteps, double automationRate,
                          int findings) {
    }

    public MigrationRecord with(Status newStatus, String started, String finished, Summary newSummary, String newError) {
        return new MigrationRecord(id, projectId, projectName, playbook, options, newStatus, createdAt,
                started == null ? startedAt : started, finished == null ? finishedAt : finished, workspace,
                newSummary == null ? summary : newSummary, newError, organisationId, startedBy);
    }

    public MigrationRecord withOrganisation(String id) {
        return new MigrationRecord(this.id, projectId, projectName, playbook, options, status, createdAt, startedAt, finishedAt,
                workspace, summary, error, id, startedBy);
    }
}
