package io.renova.core.ai;

import io.renova.core.engine.BuildError;

import java.util.List;

/**
 * @param goal     the playbook's migration goal, e.g. "Java 8 → Java 21, Jakarta EE 10"
 * @param hints    rule titles and fix hints that apply to this file
 * @param errors   build errors in this file; empty for proactive rule fixes
 */
public record FixRequest(String goal, String file, String content, List<String> hints, List<BuildError> errors) {

    public FixRequest {
        hints = List.copyOf(hints);
        errors = List.copyOf(errors);
    }
}
