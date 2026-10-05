package io.renova.core.playbook;

import java.util.List;
import java.util.Map;

/**
 * How a rule's findings are resolved. The strategy selects a {@link io.renova.core.spi.Fixer};
 * the other fields are read by whichever fixer handles it.
 *
 * @param recipes  for {@value #RECIPE}: rewrite recipes to run (OpenRewrite names for Java)
 * @param hint     guidance passed to the AI provider and printed for manual work
 * @param include  for {@value #REPLACE}: glob of files to edit
 * @param find     for {@value #REPLACE}: text (or regex if {@code regex} is true) to replace
 * @param params   options for ecosystem-specific strategies, e.g. {@code {action: setScope, scope: provided}}
 */
public record FixSpec(String strategy, List<String> recipes, String hint,
                      String include, String find, String replace, boolean regex, Map<String, Object> params) {

    /** Deterministic AST rewrite by an ecosystem tool. */
    public static final String RECIPE = "recipe";
    /** Data-driven text replacement, for files no AST tool understands (JSP, TLD, shell, config). */
    public static final String REPLACE = "replace";
    /** Context-dependent change made by the AI provider and checked by the build. */
    public static final String AI = "ai";
    /** Reported for a person to do. */
    public static final String MANUAL = "manual";

    public FixSpec {
        strategy = strategy == null ? MANUAL : strategy;
        recipes = recipes == null ? List.of() : List.copyOf(recipes);
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    /** Typed access to {@link #params()}, with errors that name the rule. */
    public Params params(String ruleId) {
        return new Params(ruleId, params);
    }

    public static FixSpec manual(String hint) {
        return new FixSpec(MANUAL, null, hint, null, null, null, false, null);
    }
}
