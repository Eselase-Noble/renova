package io.renova.php.detect;

import io.renova.core.model.Finding;
import io.renova.core.model.Module;
import io.renova.core.playbook.Rule;
import io.renova.core.spi.Detector;
import io.renova.core.spi.DetectorFactory;
import io.renova.core.util.Versions;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code type: phpVersionBelow, version: "8.4"} — one finding per composer.json whose lowest allowed PHP is
 * below the version, or that names no PHP version at all.
 */
public final class PhpVersionDetector implements DetectorFactory {

    @Override
    public String type() {
        return "phpVersionBelow";
    }

    @Override
    public Detector create(Rule rule) {
        String target = rule.detectParams().string("version");
        return ctx -> {
            List<Finding> findings = new ArrayList<>();
            for (Module module : ctx.model().modules()) {
                Object php = module.fact("php");
                if (php == null) {
                    findings.add(ctx.finding(rule, module.buildFile(), 0, "no PHP version required"));
                } else if (Versions.isBelow(php.toString(), target)) {
                    findings.add(ctx.finding(rule, module.buildFile(), 0, "PHP " + php + " → " + target));
                }
            }
            return findings;
        };
    }
}
