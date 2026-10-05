package io.renova.core.rag;

/**
 * One piece of retrieved context for an AI request.
 *
 * @param source  where it comes from: a project-relative path for code, the card id for knowledge
 * @param score   the retriever's own relevance score; only comparable within one retriever
 * @param why     short explanation shown to the model and recorded in the audit log
 */
public record ContextItem(String source, String content, Kind kind, double score, String why) {

    public enum Kind {
        /** A project file, sent as a reference file that cannot be edited. */
        CODE,
        /** Curated migration knowledge, sent as a note. */
        KNOWLEDGE
    }

    /** Unique across kinds, for de-duplication. */
    public String key() {
        return kind + ":" + source;
    }
}
