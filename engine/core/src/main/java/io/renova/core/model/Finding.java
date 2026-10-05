package io.renova.core.model;

import java.util.Map;

/**
 * One place in the project where a playbook rule matched.
 *
 * @param file project-relative path, using '/' separators
 * @param line 1-based line number, or 0 when the finding applies to the whole file
 * @param data structured details for the fixer, e.g. the coordinates of a dependency to add;
 *             {@code evidence} is the human-readable form
 */
public record Finding(String ruleId, Category category, Severity severity, String title,
                      String file, int line, String evidence, Map<String, String> data) {

    public Finding {
        data = data == null ? Map.of() : Map.copyOf(data);
    }
}
