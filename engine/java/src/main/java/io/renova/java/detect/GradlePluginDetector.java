package io.renova.java.detect;

import io.renova.core.model.Finding;
import io.renova.core.playbook.Params;
import io.renova.core.playbook.Rule;
import io.renova.core.spi.Detector;
import io.renova.core.spi.DetectorFactory;
import io.renova.core.util.Versions;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code type: gradlePlugin, id: org.springframework.boot, versionBelow?: "3"} — one finding per Gradle build
 * file that applies the plugin with a version ({@code id 'x' version '1.2'} or {@code id("x") version "1.2"})
 * older than {@code versionBelow}. A version given by a variable is reported: better a false alarm than a miss.
 */
public final class GradlePluginDetector implements DetectorFactory {

    @Override
    public String type() {
        return "gradlePlugin";
    }

    @Override
    public Detector create(Rule rule) {
        Params params = rule.detectParams();
        String id = params.string("id");
        String versionBelow = params.optString("versionBelow").orElse(null);
        Pattern applied = declaration(id);
        return ctx -> {
            List<Finding> findings = new ArrayList<>();
            for (Path gradle : ctx.files(List.of("**/build.gradle", "**/build.gradle.kts", "**/settings.gradle", "**/settings.gradle.kts"))) {
                List<String> lines = ctx.lines(gradle);
                for (int i = 0; i < lines.size(); i++) {
                    Matcher m = applied.matcher(lines.get(i));
                    if (m.find() && (versionBelow == null || below(m.group(1), versionBelow))) {
                        findings.add(ctx.finding(rule, gradle, i + 1, id + ":" + m.group(1)));
                    }
                }
            }
            return findings;
        };
    }

    /**
     * The plugin applied with a version: group 1 is the version as written. A Kotlin plugin may also be
     * written {@code kotlin("jvm")}, the Kotlin DSL's short form of {@code id("org.jetbrains.kotlin.jvm")}.
     */
    public static Pattern declaration(String id) {
        String applied = "id\\s*\\(?\\s*['\"]" + Pattern.quote(id) + "['\"]\\s*\\)?";
        if (id.startsWith(KOTLIN)) {
            applied = "(?:" + applied + "|kotlin\\s*\\(\\s*['\"]" + Pattern.quote(id.substring(KOTLIN.length())) + "['\"]\\s*\\))";
        }
        return Pattern.compile(applied + "\\s*version\\s*\\(?\\s*['\"]?([^'\"\\s)]+)");
    }

    private static final String KOTLIN = "org.jetbrains.kotlin.";

    private static boolean below(String version, String bound) {
        return !version.matches("\\d.*") || Versions.isBelow(version, bound);
    }
}
