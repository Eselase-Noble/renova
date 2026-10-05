package io.renova.core.rag;

import io.renova.core.engine.BuildError;
import io.renova.core.playbook.KnowledgeCard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lexical retrieval over a playbook's knowledge cards. A card is returned only when one of its
 * triggers appears in the request's rules, errors or target files; cards are ranked by the number of
 * matched triggers plus their BM25 score against the rules and errors. Needs no model or key.
 *
 * <p>BM25 alone does not select cards: rule hints are long and share common migration words
 * ("removed", "dependency", "Spring") with most cards. On the inventory-platform benchmark every card
 * it added on its own was unrelated, and input tokens rose by about 70% with no change in outcome.
 */
public final class KnowledgeRetriever implements Retriever {

    private static final double TRIGGER_WEIGHT = 3.0;
    private static final double K1 = 1.2;
    private static final double B = 0.75;
    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9]+");
    private static final Pattern CAMEL = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");
    private static final Set<String> STOP = Set.of("the", "and", "for", "with", "this", "that", "are", "not", "use",
            "from", "its", "has", "have", "can", "but", "was", "all", "any", "one", "when", "into", "does", "must", "java");

    private final List<KnowledgeCard> cards;
    private final List<Map<String, Integer>> termCounts = new ArrayList<>();
    private final int[] lengths;
    private final Map<String, Integer> documentFrequency = new HashMap<>();
    private final double averageLength;

    public KnowledgeRetriever(List<KnowledgeCard> cards) {
        this.cards = List.copyOf(cards);
        this.lengths = new int[cards.size()];
        long total = 0;
        for (int i = 0; i < cards.size(); i++) {
            KnowledgeCard card = cards.get(i);
            List<String> terms = terms(card.title() + " " + String.join(" ", card.triggers()) + " " + card.body());
            Map<String, Integer> counts = new HashMap<>();
            terms.forEach(t -> counts.merge(t, 1, Integer::sum));
            termCounts.add(counts);
            counts.keySet().forEach(t -> documentFrequency.merge(t, 1, Integer::sum));
            lengths[i] = terms.size();
            total += terms.size();
        }
        averageLength = cards.isEmpty() ? 1 : (double) total / cards.size();
    }

    @Override
    public String name() {
        return "knowledge";
    }

    @Override
    public List<ContextItem> retrieve(RetrievalQuery query) {
        StringBuilder request = new StringBuilder();
        query.hints().forEach(h -> request.append(h).append('\n'));
        for (BuildError e : query.errors()) {
            request.append(e.message()).append('\n');
        }
        List<String> queryTerms = terms(request.toString()).stream().distinct().toList();
        String haystack = (request + "\n" + String.join("\n", query.targets().values())).toLowerCase(Locale.ROOT);

        List<ContextItem> results = new ArrayList<>();
        for (int i = 0; i < cards.size(); i++) {
            KnowledgeCard card = cards.get(i);
            List<String> matched = card.triggers().stream()
                    .filter(t -> !t.isBlank() && haystack.contains(t.toLowerCase(Locale.ROOT))).toList();
            if (matched.isEmpty()) {
                continue;
            }
            results.add(new ContextItem(card.id(), "# " + card.title() + "\n\n" + card.body(), ContextItem.Kind.KNOWLEDGE,
                    bm25(i, queryTerms) + TRIGGER_WEIGHT * matched.size(), "the request mentions " + String.join(", ", matched)));
        }
        results.sort(Comparator.comparingDouble(ContextItem::score).reversed());
        return results;
    }

    private double bm25(int card, List<String> queryTerms) {
        Map<String, Integer> counts = termCounts.get(card);
        double score = 0;
        for (String term : queryTerms) {
            Integer tf = counts.get(term);
            if (tf == null) {
                continue;
            }
            int df = documentFrequency.get(term);
            double idf = Math.log(1 + (cards.size() - df + 0.5) / (df + 0.5));
            score += idf * tf * (K1 + 1) / (tf + K1 * (1 - B + B * lengths[card] / averageLength));
        }
        return score;
    }

    /** Lower-case words, with camelCase identifiers also split into their parts. */
    static List<String> terms(String text) {
        List<String> terms = new ArrayList<>();
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            String word = m.group();
            add(terms, word);
            String[] parts = CAMEL.split(word);
            if (parts.length > 1) {
                for (String part : parts) {
                    add(terms, part);
                }
            }
        }
        return terms;
    }

    private static void add(List<String> terms, String word) {
        String lower = word.toLowerCase(Locale.ROOT);
        if (lower.length() > 2 && !STOP.contains(lower)) {
            terms.add(lower);
        }
    }
}
