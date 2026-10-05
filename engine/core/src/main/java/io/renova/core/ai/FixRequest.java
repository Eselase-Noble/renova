package io.renova.core.ai;

import io.renova.core.engine.BuildError;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One AI request: the files to fix, the files that may change with them, and why.
 *
 * @param goal   the playbook's migration goal, e.g. "Java 8 → Java 21, Jakarta EE 10"
 * @param hints  rule titles and fix hints; empty for build repair
 * @param errors build errors in the target files; empty for proactive rule fixes
 */
public record FixRequest(String goal, List<RequestFile> files, List<String> hints, List<BuildError> errors) {

    public FixRequest {
        files = List.copyOf(files);
        hints = List.copyOf(hints);
        errors = List.copyOf(errors);
    }

    public Set<String> editablePaths() {
        return files.stream().filter(RequestFile::editable).map(RequestFile::path).collect(Collectors.toSet());
    }

    public int totalChars() {
        return files.stream().mapToInt(f -> f.content().length()).sum();
    }
}
