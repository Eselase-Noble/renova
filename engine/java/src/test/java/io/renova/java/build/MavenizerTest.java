package io.renova.java.build;

import io.renova.core.engine.StageResult;
import io.renova.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class MavenizerTest {

    @TempDir
    Path root;

    private void file(String path, String content) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private void jar(String path, String pomProperties) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry(pomProperties == null ? "com/acme/A.class" : "META-INF/maven/g/a/pom.properties"));
            zip.write((pomProperties == null ? "" : pomProperties).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    private void antProject() throws Exception {
        file("build.properties", "src.dir=src\nlevel=1.6\n");
        file("build.xml", """
                <project name="Pay Roll" default="compile">
                  <property file="build.properties"/>
                  <property name="tests" value="${basedir}/test"/>
                  <target name="compile">
                    <javac srcdir="${src.dir}" destdir="build/classes" source="${level}" target="${level}" encoding="ISO-8859-1"/>
                    <javac srcdir="${tests}" destdir="build/test-classes"/>
                  </target>
                </project>
                """);
        file("src/com/acme/Pay.java", "package com.acme;\nclass Pay {}\n");
        file("src/com/acme/labels.properties", "a=b\n");
        file("test/com/acme/PayTest.java", "package com.acme;\nclass PayTest {}\n");
        file("WebContent/WEB-INF/web.xml", "<web-app/>");
        file("build/classes/com/acme/Old.java", "stale");
        jar("WebContent/WEB-INF/lib/acme-rates.jar", null);
        jar("lib/servlet-api-2.5.jar", null);
        jar("lib/ant.jar", null);
        jar("build/payroll.jar", null);
    }

    @Test
    void readsTheLayoutFromTheAntBuild() throws Exception {
        antProject();
        LegacyLayout layout = LegacyLayout.read(root).orElseThrow();
        assertThat(layout.name()).isEqualTo("Pay Roll");
        assertThat(layout.ant()).isTrue();
        assertThat(layout.sources()).containsExactly("src");
        assertThat(layout.tests()).containsExactly("test");
        assertThat(layout.webRoot()).isEqualTo("WebContent");
        assertThat(layout.javaVersion()).isEqualTo("6");
        assertThat(layout.encoding()).isEqualTo("ISO-8859-1");
        // Build output and Ant's own libraries are not the application's.
        assertThat(layout.jars()).containsExactly("WebContent/WEB-INF/lib/acme-rates.jar", "lib/servlet-api-2.5.jar");
    }

    @Test
    void givesAnAntProjectAMavenBuildInTheStandardLayout() throws Exception {
        antProject();
        StageResult stage = Mavenizer.apply(root, false).orElseThrow();
        assertThat(stage.status()).isEqualTo(StageResult.Status.APPLIED);
        assertThat(root.resolve("src/main/java/com/acme/Pay.java")).exists();
        assertThat(root.resolve("src/main/resources/com/acme/labels.properties")).exists();
        assertThat(root.resolve("src/test/java/com/acme/PayTest.java")).exists();
        assertThat(root.resolve("src/main/java/test")).doesNotExist();
        assertThat(root.resolve("src/main/webapp/WEB-INF/web.xml")).exists();
        assertThat(root.resolve("WebContent")).doesNotExist();
        // Offline nothing can be shown to be published: the libraries stay files, in a repository in the project.
        assertThat(root.resolve("renova-libs/local/acme-rates/0/acme-rates-0.jar")).exists();
        assertThat(root.resolve("WebContent/WEB-INF/lib/acme-rates.jar")).doesNotExist();
        String pom = Files.readString(root.resolve("pom.xml"));
        assertThat(pom).contains("<artifactId>pay-roll</artifactId>", "<packaging>war</packaging>",
                "<maven.compiler.source>1.6</maven.compiler.source>", "<project.build.sourceEncoding>ISO-8859-1<",
                "<url>file://${project.basedir}/renova-libs</url>", "maven-surefire-plugin", "maven-war-plugin");
        assertThat(pom).containsPattern("(?s)<artifactId>servlet-api</artifactId>\\s*<version>2.5</version>\\s*<scope>provided</scope>");
        // Afterwards it is an ordinary Maven project.
        assertThat(new JavaPlugin().model(root).modules().getFirst().fact("buildTool")).isEqualTo("maven");
        assertThat(new JavaPlugin().prepare(root, java.util.Map.of())).isEmpty();
    }

    @Test
    void aJarThatNamesItsCoordinatesIsADependencyBeforeTheBuildExists() throws Exception {
        antProject();
        jar("lib/commons-lang-2.6.jar", "groupId=commons-lang\nartifactId=commons-lang\nversion=2.6\n");
        JavaPlugin plugin = new JavaPlugin();
        assertThat(plugin.supports(root)).isTrue();
        var module = plugin.model(root).modules().getFirst();
        assertThat(module.fact("buildTool")).isEqualTo("ant");
        assertThat(module.fact("javaVersion")).isEqualTo("6");
        assertThat(module.fact("packaging")).isEqualTo("war");
        assertThat(module.fact("dependencies").toString()).contains("commons-lang:commons-lang:2.6");
    }

    @Test
    void sourcesWithoutAnyBuildFileAreFoundByFolderName() throws Exception {
        file("src/com/acme/A.java", "package com.acme;\nclass A {}\n");
        LegacyLayout layout = LegacyLayout.read(root).orElseThrow();
        assertThat(layout.ant()).isFalse();
        assertThat(layout.sources()).containsExactly("src");
        assertThat(layout.javaVersion()).isNull();
        assertThat(Mavenizer.apply(root, false).orElseThrow().summary()).contains("from the project's folders");
        assertThat(Files.readString(root.resolve("pom.xml"))).contains("<packaging>jar</packaging>", "<maven.compiler.source>1.8<");
    }

    @Test
    void aFolderWithoutJavaSourcesIsNotAProject() throws Exception {
        file("build.xml", "<project name=\"docs\"/>");
        file("readme.txt", "nothing here");
        assertThat(LegacyLayout.read(root)).isEmpty();
        assertThat(new JavaPlugin().supports(root)).isFalse();
        assertThat(new JavaPlugin().unsupportedReason(root)).get().asString().contains("Ant project");
    }

    private void severalAntProjects() throws Exception {
        file("build.xml", """
                <project name="depot" default="all">
                  <target name="all">
                    <ant dir="stock" target="jar"/>
                    <ant antfile="web/build.xml" target="war"/>
                    <ant dir="docs"/>
                  </target>
                </project>
                """);
        jar("lib/junit-4.12.jar", "groupId=junit\nartifactId=junit\nversion=4.12\n");
        jar("lib/servlet-api.jar", null);
        file("docs/build.xml", "<project name=\"docs\"/>");
        file("stock/build.xml", """
                <project name="depot-stock" default="jar">
                  <target name="jar"><javac srcdir="src" destdir="build/classes" source="1.6" target="1.6"/></target>
                </project>
                """);
        file("stock/src/com/acme/stock/Shelf.java", "package com.acme.stock;\npublic class Shelf {}\n");
        file("stock/src/com/acme/stock/units.properties", "unit=pieces\n");
        file("stock/test/com/acme/stock/ShelfTest.java", "package com.acme.stock;\nclass ShelfTest {}\n");
        jar("stock/lib/acme-rates.jar", null);
        file("web/build.xml", """
                <project name="depot-web" default="war">
                  <property name="stock.jar" location="../stock/build/depot-stock.jar"/>
                  <path id="cp"><fileset dir="../lib" includes="*.jar"/><pathelement location="${stock.jar}"/></path>
                  <target name="war"><javac srcdir="src" destdir="build/classes" classpathref="cp"/></target>
                </project>
                """);
        file("web/src/com/acme/web/StockServlet.java", "package com.acme.web;\nclass StockServlet {}\n");
        file("web/WebContent/WEB-INF/web.xml", "<web-app/>");
    }

    @Test
    void aBuildOfSeveralAntProjectsIsReadAsModules() throws Exception {
        severalAntProjects();

        assertThat(LegacyLayout.read(root)).isEmpty();
        // The folder the root build calls that has no sources is not a module.
        assertThat(LegacyLayout.modules(root)).containsExactly("stock", "web");
        assertThat(LegacyLayout.uses(root, "web", java.util.List.of("stock", "web"))).containsExactly("stock");
        assertThat(LegacyLayout.uses(root, "stock", java.util.List.of("stock", "web"))).isEmpty();

        JavaPlugin plugin = new JavaPlugin();
        assertThat(plugin.supports(root)).isTrue();
        assertThat(plugin.model(root).modules()).extracting(io.renova.core.model.Module::name, io.renova.core.model.Module::path,
                io.renova.core.model.Module::buildFile).containsExactly(
                org.assertj.core.groups.Tuple.tuple("depot-stock", "stock", "stock/build.xml"),
                org.assertj.core.groups.Tuple.tuple("depot-web", "web", "web/build.xml"));
    }

    @Test
    void severalAntProjectsBecomeModulesOfOneParent() throws Exception {
        severalAntProjects();

        StageResult stage = Mavenizer.apply(root, false).orElseThrow();

        assertThat(stage.status()).isEqualTo(StageResult.Status.APPLIED);
        String parent = Files.readString(root.resolve("pom.xml"));
        assertThat(parent).contains("<packaging>pom</packaging>", "<module>stock</module>", "<module>web</module>",
                "<artifactId>junit</artifactId>", "<scope>test</scope>", "<scope>provided</scope>",
                "<url>file://${project.basedir}/renova-libs</url>").doesNotContain("<module>docs</module>");
        String stock = Files.readString(root.resolve("stock/pom.xml"));
        assertThat(stock).contains("<relativePath>../pom.xml</relativePath>", "<artifactId>depot-stock</artifactId>",
                "<packaging>jar</packaging>", "<artifactId>acme-rates</artifactId>", "<maven.compiler.source>1.6</maven.compiler.source>",
                "<url>file://${project.basedir}/../renova-libs</url>").doesNotContain("<artifactId>junit</artifactId>");
        String web = Files.readString(root.resolve("web/pom.xml"));
        assertThat(web).contains("<packaging>war</packaging>", "<artifactId>depot-stock</artifactId>", "<version>${project.version}</version>");
        // Each project in the standard layout, its libraries in one repository folder beside the parent.
        assertThat(root.resolve("stock/src/main/java/com/acme/stock/Shelf.java")).exists();
        assertThat(root.resolve("stock/src/main/resources/com/acme/stock/units.properties")).exists();
        assertThat(root.resolve("stock/src/test/java/com/acme/stock/ShelfTest.java")).exists();
        assertThat(root.resolve("web/src/main/webapp/WEB-INF/web.xml")).exists();
        assertThat(root.resolve("renova-libs/local/acme-rates")).isDirectory();
        assertThat(root.resolve("stock/lib/acme-rates.jar")).doesNotExist();
        assertThat(new JavaPlugin().model(root).modules()).hasSize(3);
    }
}
