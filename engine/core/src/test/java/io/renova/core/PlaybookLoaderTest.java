package io.renova.core;

import io.renova.core.model.Category;
import io.renova.core.model.Severity;
import io.renova.core.playbook.FixSpec;
import io.renova.core.playbook.Playbook;
import io.renova.core.playbook.PlaybookLoader;
import io.renova.core.util.Versions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlaybookLoaderTest {

    private static Playbook load(String yaml) {
        return PlaybookLoader.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "test");
    }

    @Test
    void loadsRulesWithDefaults() {
        Playbook p = load("""
                id: demo
                name: Demo
                ecosystem: python
                settings: { tool: { level: 3 } }
                rules:
                  - id: r1
                    category: B
                    detect: { type: fileContains, include: "**/*.py", pattern: "print " }
                    fix: { strategy: replace, include: "**/*.py", find: "print ", replace: "print(" }
                  - id: r2
                    category: runtime
                    severity: blocker
                    detect: { type: fileExists, include: [setup.py] }
                """);
        assertThat(p.rules()).hasSize(2);
        assertThat(p.rules().get(0).category()).isEqualTo(Category.NAMESPACE);
        assertThat(p.rules().get(0).severity()).isEqualTo(Severity.WARNING);
        assertThat(p.rules().get(1).category()).isEqualTo(Category.RUNTIME);
        assertThat(p.rules().get(1).fix().strategy()).isEqualTo(FixSpec.MANUAL);
        assertThat(p.rules().get(1).detectParams().strings("include")).isEqualTo(List.of("setup.py"));
        assertThat(p.setting("tool.level")).isEqualTo(3);
        assertThat(p.targets()).isEqualTo(Map.of());
    }

    @Test
    void rejectsDuplicateRulesAndIncompleteFixes() {
        assertThatThrownBy(() -> load("""
                id: d
                ecosystem: java
                rules:
                  - { id: a, detect: { type: fileExists, include: x } }
                  - { id: a, detect: { type: fileExists, include: y } }
                """)).hasMessageContaining("duplicate rule id 'a'");
        assertThatThrownBy(() -> load("""
                id: d
                ecosystem: java
                rules:
                  - { id: a, detect: { type: fileExists, include: x }, fix: { strategy: recipe } }
                """)).hasMessageContaining("without recipes");
    }

    @Test
    void rejectsUnknownKeysSoTyposAreCaught() {
        assertThatThrownBy(() -> load("""
                id: d
                ecosystem: java
                rulez: []
                """)).hasMessageContaining("rulez");
    }

    @Test
    void comparesLegacyVersionStrings() {
        assertThat(Versions.isBelow("4.3.30.RELEASE", "6")).isTrue();
        assertThat(Versions.isBelow("6.0.0", "6")).isFalse();
        assertThat(Versions.isBelow("1.8", "21")).isTrue();
        assertThat(Versions.compare("2.6", "2.6.0")).isZero();
    }
}
