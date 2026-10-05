package io.renova.core.rag;

import java.util.List;

/** One retrieval channel (structural, lexical, semantic). Results are ordered best first. */
public interface Retriever {

    String name();

    List<ContextItem> retrieve(RetrievalQuery query);
}
