package io.renova.java;

import io.renova.core.engine.BuildError;
import io.renova.core.engine.PluginRegistry;
import io.renova.core.model.ProjectModel;
import io.renova.core.playbook.Playbook;
import io.renova.core.rag.ContextItem;
import io.renova.core.rag.KnowledgeRetriever;
import io.renova.core.rag.RetrievalQuery;
import io.renova.core.spi.RelatedFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

class JavaReferencesTest {

    @Test
    void findsSupertypesImportsAndConfigurationThatNamesTheType(@TempDir Path root) throws Exception {
        write(root, "pom.xml", "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>a</artifactId>"
                + "<version>1</version><modules><module>core</module><module>web</module></modules></project>");
        write(root, "core/pom.xml", "<project><artifactId>core</artifactId></project>");
        write(root, "web/pom.xml", "<project><artifactId>web</artifactId><packaging>war</packaging></project>");
        write(root, "core/src/main/java/com/acme/core/Item.java", "package com.acme.core;\npublic class Item {}\n");
        write(root, "web/src/main/java/com/acme/web/BaseInterceptor.java", "package com.acme.web;\nabstract class BaseInterceptor {}\n");
        write(root, "web/src/main/java/com/acme/web/Auditing.java", "package com.acme.web;\ninterface Auditing<T> {}\n");
        write(root, "web/src/main/java/com/acme/web/AuditInterceptor.java", """
                package com.acme.web;

                import java.util.List;
                import com.acme.core.Item;
                // import com.acme.core.Ghost;

                public class AuditInterceptor extends BaseInterceptor
                        implements Auditing<List<Item>>, java.io.Serializable {
                }
                """);
        write(root, "web/src/main/webapp/WEB-INF/spring/servlet-context.xml",
                "<beans><bean class=\"com.acme.web.AuditInterceptor\"/><bean class=\"org.example.External\"/></beans>");

        JavaPlugin plugin = new JavaPlugin();
        ProjectModel model = plugin.model(root);

        assertThat(plugin.referencedFiles(model, root, "web/src/main/java/com/acme/web/AuditInterceptor.java"))
                .extracting(RelatedFile::path, RelatedFile::why, RelatedFile::editable).containsExactly(
                        tuple("web/src/main/java/com/acme/web/BaseInterceptor.java", "supertype com.acme.web.BaseInterceptor", false),
                        tuple("web/src/main/java/com/acme/web/Auditing.java", "supertype com.acme.web.Auditing", false),
                        tuple("core/src/main/java/com/acme/core/Item.java", "project type com.acme.core.Item, imported here", false),
                        tuple("web/src/main/webapp/WEB-INF/spring/servlet-context.xml", "refers to com.acme.web.AuditInterceptor", false));

        // From configuration to code: the bean classes that are project types.
        assertThat(plugin.referencedFiles(model, root, "web/src/main/webapp/WEB-INF/spring/servlet-context.xml"))
                .extracting(RelatedFile::path).containsExactly("web/src/main/java/com/acme/web/AuditInterceptor.java");
    }

    @Test
    void bundledKnowledgeCardsMatchRealMigrationProblems() {
        Playbook playbook = PluginRegistry.load().playbook("java8-to-21-jakarta-ee10");
        assertThat(playbook.knowledge()).hasSizeGreaterThanOrEqualTo(10);
        KnowledgeRetriever retriever = new KnowledgeRetriever(playbook.knowledge());

        assertThat(retriever.retrieve(new RetrievalQuery(
                Map.of("ReorderRules.java", "engine = new ScriptEngineManager().getEngineByName(\"nashorn\");"), List.of(), List.of())))
                .extracting(ContextItem::source).first().isEqualTo("nashorn-removed");
        assertThat(retriever.retrieve(new RetrievalQuery(Map.of("Startup.java", "class Startup {}"), List.of(),
                List.of(new BuildError("Startup.java", 3, "package jakarta.annotation does not exist")))))
                .extracting(ContextItem::source).first().isEqualTo("jdk-removed-java-ee-modules");
        assertThat(retriever.retrieve(new RetrievalQuery(Map.of("Plain.java", "class Plain { int x; }"), List.of(), List.of())))
                .isEmpty();
    }

    private static void write(Path root, String path, String content) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
