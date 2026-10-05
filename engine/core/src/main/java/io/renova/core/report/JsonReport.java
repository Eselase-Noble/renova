package io.renova.core.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.renova.core.engine.AnalysisResult;
import io.renova.core.engine.MigrationOutcome;
import io.renova.core.engine.MigrationPlan;
import io.renova.core.engine.PlanStep;
import io.renova.core.model.Finding;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Machine-readable output for CI gates, dashboards and the future web console. */
public final class JsonReport {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private JsonReport() {
    }

    public static String render(AnalysisResult analysis, MigrationPlan plan, MigrationOutcome outcome) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("schema", "renova/report/v1");
        doc.put("project", Map.of(
                "root", analysis.project().root().toString(),
                "ecosystem", analysis.project().ecosystem(),
                "modules", analysis.project().modules(),
                "facts", analysis.project().facts()));
        doc.put("playbook", Map.of("id", plan.playbook().id(), "name", plan.playbook().name(),
                "version", String.valueOf(plan.playbook().version())));
        doc.put("summary", Map.of(
                "findings", analysis.findings().size(),
                "byCategory", analysis.countByCategory(),
                "byStrategy", plan.occurrencesByStrategy(),
                "automationRate", plan.automationRate()));
        doc.put("plan", plan.steps().stream().map(JsonReport::step).toList());
        doc.put("findings", analysis.findings().stream().map(JsonReport::finding).toList());
        doc.put("warnings", analysis.warnings());
        if (outcome != null) {
            doc.put("migration", outcome);
        }
        try {
            return JSON.writeValueAsString(doc);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, Object> step(PlanStep step) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("order", step.order());
        m.put("rule", step.rule().id());
        m.put("title", step.rule().title());
        m.put("category", step.rule().category());
        m.put("severity", step.rule().severity());
        m.put("strategy", step.strategy());
        m.put("occurrences", step.occurrences());
        m.put("files", step.files());
        if (!step.rule().fix().recipes().isEmpty()) {
            m.put("recipes", step.rule().fix().recipes());
        }
        if (step.rule().fix().hint() != null) {
            m.put("hint", step.rule().fix().hint());
        }
        return m;
    }

    private static List<Object> finding(Finding f) {
        // Compact row form keeps reports for large codebases readable and small.
        return List.of(f.ruleId(), f.file(), f.line(), f.evidence() == null ? "" : f.evidence());
    }
}
