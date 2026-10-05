package io.renova.core.rag;

import io.renova.core.engine.MigrationContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Combines the retrievers' results for one AI request: reciprocal rank fusion (so no retriever's
 * score scale dominates), de-duplication, and a size budget. Target and related files always come
 * first; retrieved items only fill the space left, best first.
 */
public final class ContextAssembler {

    /** The usual reciprocal rank fusion constant: damps the advantage of the very top ranks. */
    private static final int RRF_K = 60;

    private final List<Retriever> retrievers;
    private final int budgetChars;

    public ContextAssembler(List<Retriever> retrievers, int budgetChars) {
        this.retrievers = List.copyOf(retrievers);
        this.budgetChars = budgetChars;
    }

    /** Structural and knowledge retrieval for a migration, or null when RAG is off. */
    public static ContextAssembler forMigration(MigrationContext context, int maxRequestChars) {
        RagSettings rag = context.options().rag();
        if (!rag.enabled()) {
            return null;
        }
        return new ContextAssembler(List.of(
                new StructuralRetriever(context.plugin(), context.project(), context.workspace().root()),
                new KnowledgeRetriever(context.playbook().knowledge())), (int) (maxRequestChars * rag.budget()));
    }

    /**
     * @param included  paths already in the request, which are not repeated
     * @param available characters the request can still take; the smaller of this and the budget is used
     */
    public List<ContextItem> assemble(RetrievalQuery query, Set<String> included, int available) {
        Map<String, ContextItem> best = new LinkedHashMap<>();
        Map<String, Double> fused = new LinkedHashMap<>();
        for (Retriever retriever : retrievers) {
            List<ContextItem> ranked = retriever.retrieve(query);
            for (int rank = 0; rank < ranked.size(); rank++) {
                ContextItem item = ranked.get(rank);
                if (item.kind() == ContextItem.Kind.CODE && included.contains(item.source())) {
                    continue;
                }
                best.putIfAbsent(item.key(), item);
                fused.merge(item.key(), 1.0 / (RRF_K + rank + 1), Double::sum);
            }
        }
        List<String> order = new ArrayList<>(fused.keySet());
        order.sort(Comparator.comparingDouble((String k) -> fused.get(k)).reversed());

        int remaining = Math.min(budgetChars, available);
        List<ContextItem> chosen = new ArrayList<>();
        for (String key : order) {
            ContextItem item = best.get(key);
            if (item.content().length() <= remaining) {
                chosen.add(item);
                remaining -= item.content().length();
            }
        }
        return chosen;
    }
}
