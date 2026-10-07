package io.renova.dotnet.detect;

import io.renova.core.model.Finding;
import io.renova.core.model.Module;
import io.renova.core.playbook.Rule;
import io.renova.core.spi.Detector;
import io.renova.core.spi.DetectorFactory;

import java.util.ArrayList;
import java.util.List;

/** {@code type: projectKind, kinds: [aspnet, wpf]} — one finding per project of one of the kinds. */
public final class ProjectKindDetector implements DetectorFactory {

    @Override
    public String type() {
        return "projectKind";
    }

    @Override
    public Detector create(Rule rule) {
        List<String> kinds = rule.detectParams().requiredStrings("kinds");
        return ctx -> {
            List<Finding> findings = new ArrayList<>();
            for (Module module : ctx.model().modules()) {
                if (kinds.contains(String.valueOf(module.fact("kind")))) {
                    findings.add(ctx.finding(rule, module.buildFile(), 0, module.fact("kind") + " project"));
                }
            }
            return findings;
        };
    }
}
