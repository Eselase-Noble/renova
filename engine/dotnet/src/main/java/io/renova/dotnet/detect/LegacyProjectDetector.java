package io.renova.dotnet.detect;

import io.renova.core.model.Finding;
import io.renova.core.model.Module;
import io.renova.core.playbook.Rule;
import io.renova.core.spi.Detector;
import io.renova.core.spi.DetectorFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code type: legacyProjectFormat, kinds?: [library, exe, test], exceptKinds?: [aspnet]} — one finding per
 * project file in the format from before the .NET SDK, optionally only for (or except) some kinds of project.
 */
public final class LegacyProjectDetector implements DetectorFactory {

    @Override
    public String type() {
        return "legacyProjectFormat";
    }

    @Override
    public Detector create(Rule rule) {
        List<String> kinds = rule.detectParams().strings("kinds");
        List<String> except = rule.detectParams().strings("exceptKinds");
        return ctx -> {
            List<Finding> findings = new ArrayList<>();
            for (Module module : ctx.model().modules()) {
                String kind = String.valueOf(module.fact("kind"));
                if (Boolean.FALSE.equals(module.fact("sdkStyle")) && (kinds.isEmpty() || kinds.contains(kind))
                        && !except.contains(kind)) {
                    findings.add(ctx.finding(rule, module.buildFile(), 0, kind + " project in the pre-SDK format"));
                }
            }
            return findings;
        };
    }
}
