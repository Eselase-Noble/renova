package io.renova.core.engine;

import io.renova.core.ai.AiProvider;
import io.renova.core.model.ProjectModel;
import io.renova.core.playbook.Playbook;
import io.renova.core.workspace.Workspace;

/**
 * Everything a fixer or verifier may use. {@code project} describes the original source; its
 * relative paths are equally valid inside the workspace.
 */
public record MigrationContext(Workspace workspace, ProjectModel project, Playbook playbook,
                               MigrationOptions options, AiProvider ai) {
}
