package io.renova.intellij;

import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** The plugin inside a headless IDE: a real assessment of a small project, shown by the editor inspection. */
public class RenovaPluginTest extends BasePlatformTestCase {

    private Path root;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        root = Files.createTempDirectory("renova-plugin-test");
        Files.writeString(root.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>legacy</artifactId>
                <version>1</version><packaging>war</packaging>
                <properties><maven.compiler.source>1.8</maven.compiler.source></properties>
                <dependencies><dependency><groupId>javax.servlet</groupId><artifactId>javax.servlet-api</artifactId>
                <version>3.1.0</version><scope>provided</scope></dependency></dependencies></project>
                """);
        Path src = Files.createDirectories(root.resolve("src/main/java/com/acme"));
        Files.writeString(src.resolve("Hello.java"), """
                package com.acme;

                import javax.servlet.http.HttpServlet;

                public class Hello extends HttpServlet {
                }
                """);
    }

    public void testAssessmentFindsTheMigrationWork() throws Exception {
        RenovaProjectService service = RenovaProjectService.of(getProject());
        RenovaProjectService.Assessment a = RenovaProjectService.compute(service.registry(), root);
        assertEquals("java8-to-21-jakarta-ee10", a.playbook().id());
        assertTrue(a.findingsByFile().containsKey("src/main/java/com/acme/Hello.java"));
        assertTrue(a.plan().steps().stream().anyMatch(s -> s.rule().id().equals("javax-ee-imports")));
    }

    public void testTheInspectionShowsFindingsAtTheirLines() throws Exception {
        RenovaProjectService service = RenovaProjectService.of(getProject());
        service.useAssessment(RenovaProjectService.compute(service.registry(), root));

        VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve("src/main/java/com/acme/Hello.java"));
        assertNotNull(vf);
        PsiFile file = PsiManager.getInstance(getProject()).findFile(vf);
        ProblemDescriptor[] problems = new RenovaInspection().checkFile(file, InspectionManager.getInstance(getProject()), false);

        ProblemDescriptor importLine = Arrays.stream(problems)
                .filter(p -> p.getDescriptionTemplate().contains("Rename javax.* Java EE packages to jakarta.*"))
                .findFirst().orElseThrow(() -> new AssertionError("no javax finding in " + Arrays.toString(problems)));
        assertEquals(2, importLine.getLineNumber());
        assertTrue(importLine.getDescriptionTemplate().startsWith("Renova (B): "));
        assertTrue(importLine.getDescriptionTemplate().contains("fixes this automatically"));
    }

    public void testFilesOutsideTheAssessmentStayClean() throws Exception {
        RenovaProjectService service = RenovaProjectService.of(getProject());
        PsiFile other = myFixture.configureByText("Other.java", "import javax.servlet.http.HttpServlet; class Other {}");
        assertEquals(0, new RenovaInspection().checkFile(other, InspectionManager.getInstance(getProject()), false).length);
        service.useAssessment(RenovaProjectService.compute(service.registry(), root));
        assertEquals(0, new RenovaInspection().checkFile(other, InspectionManager.getInstance(getProject()), false).length);
    }
}
