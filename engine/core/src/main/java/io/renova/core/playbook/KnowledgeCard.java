package io.renova.core.playbook;

import java.util.List;

/**
 * Curated migration knowledge shipped with a playbook: a short note written from public sources
 * (framework migration guides, container release notes) and experience. Cards are retrieved for AI
 * requests whose rules, errors or files mention one of the card's triggers. They never contain
 * customer code.
 *
 * @param triggers terms that make the card relevant, matched case-insensitively against the
 *                 request's rules, build errors and target files, e.g. "CommonsMultipartResolver"
 */
public record KnowledgeCard(String id, String title, List<String> triggers, String body) {

    public KnowledgeCard {
        if (id == null || id.isBlank() || body == null || body.isBlank()) {
            throw new IllegalArgumentException("Knowledge cards need an id and a body");
        }
        title = title == null ? id : title;
        triggers = triggers == null ? List.of() : List.copyOf(triggers);
        body = body.strip();
    }
}
