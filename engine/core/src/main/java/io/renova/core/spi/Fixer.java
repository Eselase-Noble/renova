package io.renova.core.spi;

import io.renova.core.engine.MigrationContext;
import io.renova.core.engine.PlanStep;
import io.renova.core.engine.StageResult;

import java.util.List;

/** Applies every plan step that uses one fix strategy (recipe, replace, ai, ...). */
public interface Fixer {

    String strategy();

    StageResult apply(MigrationContext context, List<PlanStep> steps) throws Exception;
}
