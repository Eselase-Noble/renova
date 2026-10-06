package io.renova.java.fix;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class RecipeBundlesTest {

    @TempDir
    Path folder;

    private Path jar() throws Exception {
        Path jar = folder.resolve("recipes.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (String[] file : new String[][] {
                    {"updates/core/3.10.alpha1.yaml", "ten"}, {"updates/core/3.2.alpha1.yaml", "two"},
                    {"updates/core/4.0.alpha1.yaml", "four"}, {"updates/other/3.0.yaml", "other"},
                    {"updates/core/nested/3.1.yaml", "nested"}}) {
                zip.putNextEntry(new ZipEntry(file[0]));
                zip.write(("#####\n---\ntype: specs.openrewrite.org/v1beta/recipe\nname: com.example." + file[1]
                        + "\nrecipeList:\n  - org.example.Something\n").getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return jar;
    }

    @Test
    void joinsTheFilesInReleaseOrderUpToTheTarget() throws Exception {
        RecipeBundles.Bundle bundle = new RecipeBundles.Bundle("io.renova.Update", "g:a:1", "updates/core/", "3.33",
                List.of(Map.of("org.openrewrite.maven.ChangePropertyValue", Map.of("key", "x.version")), "org.example.Plain"));
        String yaml = RecipeBundles.configuration(bundle, jar());
        assertThat(yaml).contains("name: com.example.two", "name: com.example.ten")
                .doesNotContain("com.example.four", "com.example.other", "com.example.nested");
        assertThat(yaml.indexOf("name: com.example.two")).isLessThan(yaml.indexOf("name: com.example.ten"));
        assertThat(yaml).endsWith("""
                name: io.renova.Update
                displayName: io.renova.Update
                description: The recipes of g a 1 in release order.
                recipeList:
                  - org.openrewrite.maven.ChangePropertyValue:
                      key: "x.version"
                  - org.example.Plain
                  - com.example.two
                  - com.example.ten
                """);
    }

    @Test
    void withoutATargetEveryReleaseIsIncluded() throws Exception {
        RecipeBundles.Bundle bundle = new RecipeBundles.Bundle("io.renova.Update", "g:a:1", "updates/core/", null, List.of());
        assertThat(RecipeBundles.configuration(bundle, jar())).contains("  - com.example.four\n");
    }
}
