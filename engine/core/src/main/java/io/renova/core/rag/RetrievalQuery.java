package io.renova.core.rag;

import io.renova.core.engine.BuildError;

import java.util.List;
import java.util.Map;

/**
 * What an AI request is about, for retrievers to find context for it.
 *
 * @param targets project-relative paths of the files to fix, with their current content
 * @param hints   matched rule titles and hints; empty for build repair
 * @param errors  build errors in the targets; empty for proactive rule fixes
 */
public record RetrievalQuery(Map<String, String> targets, List<String> hints, List<BuildError> errors) {

    public RetrievalQuery {
        targets = Map.copyOf(targets);
        hints = List.copyOf(hints);
        errors = List.copyOf(errors);
    }
}
