package io.renova.java.fix;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Edits to Gradle build files, kept in the file's own style. */
class GradleBuildFixerTest {

    private static final String GROOVY = """
            buildscript {
                dependencies {
                    classpath 'org.acme:build-tools:1.0'
                }
            }

            plugins {
              id 'java'
            }

            dependencies {
              implementation 'org.springframework.boot:spring-boot-starter-web'
              runtimeOnly "javax.xml.bind:jaxb-api:2.3.1"
              testImplementation 'org.springframework.boot:spring-boot-starter-test'
            }

            tasks.named('test') {
              useJUnitPlatform()
            }
            """;

    @Test
    void addsToTheProjectsDependenciesInTheirStyle() {
        String fixed = GradleBuildFixer.addDependency(GROOVY, "implementation", "jakarta.xml.bind:jakarta.xml.bind-api", false);
        assertThat(fixed).contains("""
                  testImplementation 'org.springframework.boot:spring-boot-starter-test'
                  implementation 'jakarta.xml.bind:jakarta.xml.bind-api'
                }
                """);
        // Not into the build script's own classpath.
        assertThat(fixed).contains("        classpath 'org.acme:build-tools:1.0'\n    }\n}");
        assertThat(GradleBuildFixer.addDependency(fixed, "implementation", "jakarta.xml.bind:jakarta.xml.bind-api", false)).isEqualTo(fixed);
    }

    @Test
    void kotlinBuildsGetACall() {
        String kotlin = "plugins {\n    java\n}\n\ndependencies {\n    implementation(\"org.acme:lib:1.0\")\n}\n";
        assertThat(GradleBuildFixer.addDependency(kotlin, "compileOnly", "jakarta.servlet:jakarta.servlet-api:6.1.0", true))
                .contains("    implementation(\"org.acme:lib:1.0\")\n    compileOnly(\"jakarta.servlet:jakarta.servlet-api:6.1.0\")\n}");
    }

    @Test
    void aBuildWithoutTheBlockGetsOne() {
        assertThat(GradleBuildFixer.addDependency("plugins {\n    id 'java'\n}\n", "implementation", "org.acme:lib:1.0", false))
                .endsWith("\ndependencies {\n    implementation 'org.acme:lib:1.0'\n}\n");
    }

    @Test
    void removesADependencyWhateverItsVersion() {
        String fixed = GradleBuildFixer.removeDependency(GROOVY, "javax.xml.bind:jaxb-api");
        assertThat(fixed).doesNotContain("jaxb-api").contains("spring-boot-starter-web'\n  testImplementation");
        assertThat(GradleBuildFixer.removeDependency(GROOVY, "org.acme:missing")).isEqualTo(GROOVY);
    }

    @Test
    void setsTheVersionOfEveryPluginOfAFamily() {
        String groovy = """
                plugins {
                    id 'org.springframework.boot' version '3.5.16'
                    id 'org.jetbrains.kotlin.jvm' version '1.6.21'
                    id "org.jetbrains.kotlin.plugin.spring" version "1.6.21"
                }
                """;
        assertThat(GradleBuildFixer.setPluginVersion(groovy, java.util.List.of("org.jetbrains.kotlin."), "1.9.25")).isEqualTo("""
                plugins {
                    id 'org.springframework.boot' version '3.5.16'
                    id 'org.jetbrains.kotlin.jvm' version '1.9.25'
                    id "org.jetbrains.kotlin.plugin.spring" version "1.9.25"
                }
                """);
        String kotlin = """
                plugins {
                    id("org.springframework.boot") version "3.5.16"
                    kotlin("jvm") version "1.6.21"
                    kotlin("plugin.jpa") version "1.6.21"
                }
                """;
        assertThat(GradleBuildFixer.setPluginVersion(kotlin, java.util.List.of("org.jetbrains.kotlin."), "1.9.25")).isEqualTo("""
                plugins {
                    id("org.springframework.boot") version "3.5.16"
                    kotlin("jvm") version "1.9.25"
                    kotlin("plugin.jpa") version "1.9.25"
                }
                """);
        assertThat(io.renova.java.detect.GradlePluginDetector.declaration("org.jetbrains.kotlin.jvm").matcher(kotlin).find()).isTrue();
    }

    @Test
    void pointsTheWrapperAtAnotherGradleAndDropsTheOldChecksum() {
        String properties = """
                distributionBase=GRADLE_USER_HOME
                distributionUrl=https\\://downloads.gradle.org/distributions/gradle-8.5-bin.zip
                distributionSha256Sum=9d926787066a081739e8200858338b4a69e837c3a821a33aca9db09dd4a41026
                zipStoreBase=GRADLE_USER_HOME
                """;
        assertThat(GradleBuildFixer.setWrapperVersion(properties, "9.1.0")).isEqualTo("""
                distributionBase=GRADLE_USER_HOME
                distributionUrl=https\\://downloads.gradle.org/distributions/gradle-9.1.0-bin.zip
                zipStoreBase=GRADLE_USER_HOME
                """);
    }
}
