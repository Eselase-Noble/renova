package io.renova.java.detect;

import io.renova.core.model.Finding;
import io.renova.core.model.Module;
import io.renova.core.playbook.Rule;
import io.renova.core.spi.Detector;
import io.renova.core.spi.DetectorFactory;
import io.renova.core.util.Versions;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code type: javaVersionBelow, version: 21} — one finding per module whose declared Java level is
 * lower, or not declared at all (the compiler default of an old build is rarely what anyone wants).
 */
public final class JavaVersionDetector implements DetectorFactory {

    @Override
    public String type() {
        return "javaVersionBelow";
    }

    @Override
    public Detector create(Rule rule) {
        String target = String.valueOf(rule.detectParams().intValue("version"));
        return ctx -> {
            List<Finding> findings = new ArrayList<>();
            for (Module module : ctx.model().modules()) {
                Object declared = module.fact("javaVersion");
                if (declared == null) {
                    findings.add(ctx.finding(rule, module.buildFile(), 0, "no Java version declared"));
                } else if (Versions.isBelow(declared.toString(), target)) {
                    findings.add(ctx.finding(rule, module.buildFile(), 0, "Java " + declared + " → " + target));
                }
            }
            return findings;
        };
    }
}
