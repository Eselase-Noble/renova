package io.renova.core.model;

/**
 * One place in the project where a playbook rule matched.
 *
 * @param file project-relative path, using '/' separators
 * @param line 1-based line number, or 0 when the finding applies to the whole file
 */
public record Finding(String ruleId, Category category, Severity severity, String title,
                      String file, int line, String evidence) {
}
