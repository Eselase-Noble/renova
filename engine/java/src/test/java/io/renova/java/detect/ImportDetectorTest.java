package io.renova.java.detect;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ImportDetectorTest {

    @Test
    void readsImportsOfEveryJvmLanguage() {
        assertThat(ImportDetector.imports("import javax.servlet.http.HttpServlet;", false)).containsExactly("javax.servlet.http.HttpServlet");
        assertThat(ImportDetector.imports("import static org.junit.Assert.*;", false)).containsExactly("org.junit.Assert.*");
        // Kotlin and Groovy: no semicolon, and an alias.
        assertThat(ImportDetector.imports("import javax.persistence.Entity", false)).containsExactly("javax.persistence.Entity");
        assertThat(ImportDetector.imports("import javax.persistence.*", false)).containsExactly("javax.persistence.*");
        assertThat(ImportDetector.imports("import javax.validation.Valid as V", false)).containsExactly("javax.validation.Valid");
        // Scala: everything in a package, and a group.
        assertThat(ImportDetector.imports("import javax.inject._", false)).containsExactly("javax.inject");
        assertThat(ImportDetector.imports("import javax.ws.rs.{GET, Path}", false)).containsExactly("javax.ws.rs");
        assertThat(ImportDetector.imports("val importer = javax.x", false)).isEmpty();
    }
}
