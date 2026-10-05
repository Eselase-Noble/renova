package io.renova.cli;

import io.renova.core.ai.AiUsage;

import java.util.List;

/**
 * The score of one app migrated with one configuration.
 *
 * @param build         PASSES, FAILS, NOT_VERIFIED, or ERROR when the migration itself failed
 * @param failedChecks  descriptions of the suite checks the migrated copy did not meet
 * @param rejectedEdits AI edits refused because they touched files not offered as editable
 * @param behaviour     SAME, DIFFERENT, SKIPPED or FAILED; null when behavioural verification did not run
 * @param behaviourDifferences requests the migrated app answered differently
 */
record BenchmarkResult(String app, String configuration, int repetition, String build, int buildErrors,
                       int checksPassed, int checksTotal, List<String> failedChecks, int repairRounds,
                       AiUsage ai, int rejectedEdits, int manualSteps, double automationRate,
                       double seconds, String workspace, String error, String behaviour, int behaviourDifferences) {

    boolean passed() {
        return build.equals("PASSES") && checksPassed == checksTotal;
    }
}
