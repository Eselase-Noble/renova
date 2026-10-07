package io.renova.dotnet.detect;

import io.renova.core.model.Finding;
import io.renova.core.model.Module;
import io.renova.core.playbook.Rule;
import io.renova.core.spi.Detector;
import io.renova.core.spi.DetectorFactory;
import io.renova.dotnet.Tfm;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code type: targetFrameworkBelow, version: "10.0"} — one finding per project built for .NET Framework,
 * .NET Core, or a modern .NET older than the version. .NET Standard libraries are not reported: they run on
 * the target as they are.
 */
public final class TargetFrameworkDetector implements DetectorFactory {

    @Override
    public String type() {
        return "targetFrameworkBelow";
    }

    @Override
    public Detector create(Rule rule) {
        String target = rule.detectParams().string("version");
        return ctx -> {
            List<Finding> findings = new ArrayList<>();
            for (Module module : ctx.model().modules()) {
                if (!(module.fact("targetFrameworks") instanceof List<?> frameworks)) {
                    continue;
                }
                List<String> old = frameworks.stream().map(Object::toString).filter(t -> Tfm.below(t, target)).toList();
                if (!old.isEmpty()) {
                    findings.add(ctx.finding(rule, module.buildFile(), 0, String.join(";", old) + " → net" + target));
                }
            }
            return findings;
        };
    }
}
