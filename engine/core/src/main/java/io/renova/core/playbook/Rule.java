package io.renova.core.playbook;

import io.renova.core.model.Category;
import io.renova.core.model.Severity;

import java.util.Map;

/**
 * One thing to find and fix.
 *
 * @param detect detector type plus its parameters, e.g. {@code {type: import, prefixes: [javax.servlet]}}
 * @param fix    how the finding is resolved
 * @param phase  {@value #MIGRATE} (default): runs on the original project and plans the migration;
 *               {@value #GUARD}: runs on the migrated code before verification, to catch problems
 *               the migration introduced or left behind
 */
public record Rule(String id, String title, Category category, Severity severity,
                   Map<String, Object> detect, FixSpec fix, String rationale, String phase) {

    public static final String MIGRATE = "migrate";
    public static final String GUARD = "guard";

    public Rule {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Rule needs an id");
        }
        if (detect == null || !(detect.get("type") instanceof String)) {
            throw new IllegalArgumentException("Rule '" + id + "' needs detect.type");
        }
        title = title == null ? id : title;
        category = category == null ? Category.API : category;
        severity = severity == null ? Severity.WARNING : severity;
        detect = Map.copyOf(detect);
        fix = fix == null ? FixSpec.manual(null) : fix;
        phase = phase == null ? MIGRATE : phase;
        if (!phase.equals(MIGRATE) && !phase.equals(GUARD)) {
            throw new IllegalArgumentException("Rule '" + id + "': phase must be '" + MIGRATE + "' or '" + GUARD + "'");
        }
    }

    public boolean guard() {
        return GUARD.equals(phase);
    }

    public String detectorType() {
        return (String) detect.get("type");
    }

    public Params detectParams() {
        return new Params(id, detect);
    }
}
