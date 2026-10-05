package io.renova.core.rag;

import io.renova.core.model.ProjectModel;
import io.renova.core.spi.EcosystemPlugin;
import io.renova.core.spi.RelatedFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Code the targets depend on or that refers to them, as the ecosystem plugin understands it
 * ({@link EcosystemPlugin#referencedFiles}). Needs no model or key.
 */
public final class StructuralRetriever implements Retriever {

    private final EcosystemPlugin plugin;
    private final ProjectModel model;
    private final Path root;

    public StructuralRetriever(EcosystemPlugin plugin, ProjectModel model, Path root) {
        this.plugin = plugin;
        this.model = model;
        this.root = root;
    }

    @Override
    public String name() {
        return "structural";
    }

    @Override
    public List<ContextItem> retrieve(RetrievalQuery query) {
        List<ContextItem> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>(query.targets().keySet());
        for (String target : query.targets().keySet()) {
            List<RelatedFile> referenced = plugin.referencedFiles(model, root, target);
            for (int i = 0; i < referenced.size(); i++) {
                RelatedFile file = referenced.get(i);
                if (!seen.add(file.path())) {
                    continue;
                }
                String content = read(file.path());
                if (content != null) {
                    results.add(new ContextItem(file.path(), content, ContextItem.Kind.CODE, 1.0 / (i + 1), file.why()));
                }
            }
        }
        return results;
    }

    private String read(String file) {
        Path path = root.resolve(file).normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path)) {
            return null;
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (CharacterCodingException e) {
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
