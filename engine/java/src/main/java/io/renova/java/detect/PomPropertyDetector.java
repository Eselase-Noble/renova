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
 */
public final class PomPropertyDetector implements DetectorFactory {

    @Override
    public String type() {
        return "pomProperty";
    }

    @Override
    public Detector create(Rule rule) {
        Params params = rule.detectParams();
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
