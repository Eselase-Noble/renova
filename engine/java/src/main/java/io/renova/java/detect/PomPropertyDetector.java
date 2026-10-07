package io.renova.java.detect;

import io.renova.core.model.Finding;
import io.renova.core.playbook.Params;
import io.renova.core.playbook.Rule;
import io.renova.core.spi.Detector;
import io.renova.core.spi.DetectorFactory;
import io.renova.java.PomReader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code type: pomProperty, missing: maven.compiler.target, requires?: maven.compiler.source,
 * unless?: [maven.compiler.release]} — one finding per pom that lacks the {@code missing} property
 * while declaring {@code requires} and none of {@code unless}.
 *
 * <p>{@code type: pomProperty, name: kotlin.version, versionBelow: "1.9.25"} — one finding per pom that sets
 * the property to a version older than the bound; a value that is not a plain version is left alone.
 *
 * <p>{@code type: pomProperty, overrides: [assertj.version, ...], whenParent: "g:a"} — one finding per listed
 * property that a pom with that parent sets itself: a version its platform would otherwise manage.
 */
public final class PomPropertyDetector implements DetectorFactory {

    @Override
    public String type() {
        return "pomProperty";
    }

    @Override
    public Detector create(Rule rule) {
        Params params = rule.detectParams();
        if (!params.strings("overrides").isEmpty()) {
            List<String> names = params.strings("overrides");
            String parent = params.string("whenParent");
            return ctx -> {
                List<Finding> findings = new ArrayList<>();
                // The platform's parent is usually declared once, at the top; its modules inherit the versions.
                boolean platform = false;
                for (Path pom : ctx.files("**/pom*.xml")) {
                    platform |= parent.equals(DependencyDetector.readPom(ctx, pom).parent());
                }
                if (!platform) {
                    return findings;
                }
                for (Path pom : ctx.files("**/pom*.xml")) {
                    PomReader.Pom read = DependencyDetector.readPom(ctx, pom);
                    for (String name : names) {
                        String value = read.properties().get(name);
                        if (value != null) {
                            findings.add(ctx.finding(rule, io.renova.core.scan.ScanContext.toProjectPath(pom),
                                    DependencyDetector.lineOf(ctx, pom, "<" + name + ">"), name + "=" + value,
                                    java.util.Map.of("property", name)));
                        }
                    }
                }
                return findings;
            };
        }
        if (params.optString("name").isPresent()) {
            String name = params.string("name");
            String bound = params.string("versionBelow");
            return ctx -> {
                List<Finding> findings = new ArrayList<>();
                for (Path pom : ctx.files("**/pom*.xml")) {
                    String value = DependencyDetector.readPom(ctx, pom).properties().get(name);
                    if (value != null && value.matches("\\d[\\w.\\-]*") && io.renova.core.util.Versions.isBelow(value, bound)) {
                        findings.add(ctx.finding(rule, pom, DependencyDetector.lineOf(ctx, pom, "<" + name + ">"), name + "=" + value));
                    }
                }
                return findings;
            };
        }
        String missing = params.string("missing");
        String requires = params.optString("requires").orElse(null);
        List<String> unless = params.strings("unless");
        return ctx -> {
            List<Finding> findings = new ArrayList<>();
            for (Path pom : ctx.files("**/pom*.xml")) {
                PomReader.Pom read = DependencyDetector.readPom(ctx, pom);
                var props = read.properties();
                if (props.containsKey(missing) || (requires != null && !props.containsKey(requires))
                        || unless.stream().anyMatch(props::containsKey)) {
                    continue;
                }
                int line = requires == null ? 0 : DependencyDetector.lineOf(ctx, pom, "<" + requires + ">");
                findings.add(ctx.finding(rule, pom, line, missing + " not set"
                        + (requires == null ? "" : " although " + requires + " is")));
            }
            return findings;
        };
    }
}
