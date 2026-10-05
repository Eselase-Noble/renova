package io.renova.core;

import io.renova.core.ai.AiFixer;
import io.renova.core.ai.AiSettings;
import io.renova.core.ai.EditPrompt;
import io.renova.core.ai.FixRequest;
import io.renova.core.ai.RequestFile;
import io.renova.core.engine.BuildError;
import io.renova.core.engine.MigrationContext;
import io.renova.core.engine.MigrationOptions;
import io.renova.core.engine.VerifyResult;
import io.renova.core.model.ProjectModel;
import io.renova.core.playbook.KnowledgeCard;
import io.renova.core.playbook.Playbook;
import io.renova.core.rag.ContextAssembler;
import io.renova.core.rag.ContextItem;
import io.renova.core.rag.KnowledgeRetriever;
import io.renova.core.rag.RagSettings;
import io.renova.core.rag.RetrievalQuery;
import io.renova.core.rag.Retriever;
import io.renova.core.spi.RelatedFile;
import io.renova.core.workspace.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

class RagTest {

    private static final List<KnowledgeCard> CARDS = List.of(
            new KnowledgeCard("multipart", "Spring 6 removed CommonsMultipartResolver", List.of("CommonsMultipartResolver"),
                    "Use StandardServletMultipartResolver and move limits to multipart-config."),
            new KnowledgeCard("nashorn", "Nashorn was removed in Java 15", List.of("nashorn", "getEngineByName"),
                    "Add org.openjdk.nashorn:nashorn-core."),
            new KnowledgeCard("lib", "The lib library moved", List.of("package lib does not exist"),
                    "Declare lib in the build file."));

    @Test
    void knowledgeCardsAreFoundByTheirTriggersInFilesAndErrors() {
        KnowledgeRetriever retriever = new KnowledgeRetriever(CARDS);

        List<ContextItem> fromFile = retriever.retrieve(new RetrievalQuery(
                Map.of("servlet-context.xml", "<bean class=\"org.springframework.web.multipart.commons.CommonsMultipartResolver\"/>"),
                List.of("Replace the multipart resolver"), List.of()));
        assertThat(fromFile).extracting(ContextItem::source, ContextItem::why)
                .containsExactly(tuple("multipart", "the request mentions CommonsMultipartResolver"));

        List<ContextItem> fromError = retriever.retrieve(new RetrievalQuery(Map.of("A.java", "class A {}"), List.of(),
                List.of(new BuildError("A.java", 1, "package lib does not exist"))));
        assertThat(fromError).extracting(ContextItem::source).containsExactly("lib");

        assertThat(retriever.retrieve(new RetrievalQuery(Map.of("B.java", "class B {}"), List.of("Rename packages"), List.of())))
                .isEmpty();
        // Sharing many words is not enough: only a trigger selects a card.
        assertThat(retriever.retrieve(new RetrievalQuery(Map.of("C.java", "class C {}"),
                List.of("Use StandardServletMultipartResolver and move limits to multipart-config; Spring 6 removed it"), List.of())))
                .isEmpty();
    }

    @Test
    void assemblerFusesRanksSkipsIncludedFilesAndKeepsToTheBudget() {
        ContextItem shared = new ContextItem("Base.java", "x".repeat(40), ContextItem.Kind.CODE, 1, "supertype");
        ContextItem onlyFirst = new ContextItem("Other.java", "y".repeat(40), ContextItem.Kind.CODE, 0.9, "imported");
        ContextItem target = new ContextItem("A.java", "z", ContextItem.Kind.CODE, 0.8, "already in the request");
        ContextItem large = new ContextItem("Huge.java", "h".repeat(500), ContextItem.Kind.CODE, 0.7, "too big");
        Retriever first = retriever(List.of(onlyFirst, shared, target, large));
        Retriever second = retriever(List.of(shared));

        List<ContextItem> chosen = new ContextAssembler(List.of(first, second), 1000)
                .assemble(new RetrievalQuery(Map.of(), List.of(), List.of()), Set.of("A.java"), 100);

        // Found by both retrievers, so ranked first; A.java is already included; Huge.java does not fit.
        assertThat(chosen).extracting(ContextItem::source).containsExactly("Base.java", "Other.java");
    }

    @Test
    void retrievedCodeIsReferenceOnlyAndKnowledgeIsSentAsNotes(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("project/src"));
        Files.writeString(project.resolve("A.java"), "class A extends Base {}\n");
        Files.writeString(project.resolve("Base.java"), "class Base { /* uses lib */ }\n");
        Files.writeString(project.getParent().resolve("build.txt"), "deps:\n");
        Workspace ws = Workspace.create(project.getParent(), tmp.resolve("out"));
        AiFixerTest.ToyPlugin plugin = new AiFixerTest.ToyPlugin() {
            @Override
            public List<RelatedFile> referencedFiles(ProjectModel model, Path root, String file) {
                return file.equals("src/A.java") ? List.of(new RelatedFile("src/Base.java", true, "supertype Base")) : List.of();
            }
        };
        Playbook playbook = new Playbook("p", "Toy upgrade", null, "toy", "1", null, null, null, CARDS);
        AiFixerTest.ScriptedAi ai = new AiFixerTest.ScriptedAi(request -> Map.of(
                "build.txt", "deps:\n  lib\n",
                "src/Base.java", "changed by mistake\n"));
        MigrationContext ctx = new MigrationContext(ws, plugin.model(ws.root()), playbook,
                new MigrationOptions(tmp.resolve("out"), AiSettings.NONE, 1, true, Map.of(), List.of(), RagSettings.ON), ai, plugin);
        Path root = ws.root();
        VerifyResult failing = new VerifyResult(false, List.of(new BuildError("src/A.java", 1, "package lib does not exist")), "");

        List<String> log = new ArrayList<>();
        new AiFixer().repair(ctx, c -> new VerifyResult(true, List.of(), ""), failing, 1, log);

        FixRequest request = ai.requests.getFirst();
        assertThat(request.files()).extracting(RequestFile::path, RequestFile::role).containsExactly(
                tuple("src/A.java", RequestFile.Role.TARGET),
                tuple("build.txt", RequestFile.Role.RELATED),
                tuple("src/Base.java", RequestFile.Role.REFERENCE));
        assertThat(request.knowledge()).extracting(ContextItem::source).containsExactly("lib");
        assertThat(EditPrompt.userMessage(request))
                .contains("<knowledge id=\"lib\" why=\"the request mentions package lib does not exist\">\n# The lib library moved");
        assertThat(Files.readString(root.resolve("src/Base.java"))).isEqualTo("class Base { /* uses lib */ }\n");
        assertThat(Files.readString(root.resolve(".renova/ai/001.md")))
                .contains("`src/Base.java` (reference: supertype Base)", "## Knowledge notes", "`lib` (the request mentions");
        assertThat(log).anyMatch(l -> l.contains("2 retrieved context item(s)"));
    }

    @Test
    void ragIsOnByDefault(@TempDir Path tmp) throws Exception {
        assertThat(new MigrationOptions(tmp, AiSettings.NONE, 0, false, Map.of(), List.of()).rag().enabled()).isTrue();
    }

    private static Retriever retriever(List<ContextItem> items) {
        return new Retriever() {
            public String name() { return "fixed"; }
            public List<ContextItem> retrieve(RetrievalQuery query) { return items; }
        };
    }
}
