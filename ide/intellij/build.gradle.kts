import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "io.renova"
version = providers.gradleProperty("pluginVersion").get()

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    // The Renova engine: run `mvn install` in the repository root first.
    mavenLocal()
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

val renova = providers.gradleProperty("renovaVersion").get()

dependencies {
    implementation("io.renova:renova-core:$renova")
    implementation("io.renova:renova-java:$renova")
    implementation("io.renova:renova-ai-anthropic:$renova")
    implementation("io.renova:renova-ai-openai:$renova")

    intellijPlatform {
        val local = providers.gradleProperty("platformPath").orNull
        if (local != null) {
            local(local)
        } else {
            intellijIdeaCommunity(providers.gradleProperty("platformVersion").get())
        }
        bundledPlugin("com.intellij.java")
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

configurations.runtimeClasspath {
    // The IDE provides Kotlin's standard library; a second copy in the plugin causes conflicts.
    exclude(group = "org.jetbrains.kotlin")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252"
        }
    }
    pluginVerification {
        ides {
            recommended()
        }
    }
}
