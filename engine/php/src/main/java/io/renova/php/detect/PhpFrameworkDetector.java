package io.renova.php.detect;

import io.renova.core.model.Finding;
import io.renova.core.model.Module;
import io.renova.core.playbook.Rule;
import io.renova.core.spi.Detector;
import io.renova.core.spi.DetectorFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code type: phpFramework, is: [none]} — one finding per project built on one of the frameworks named:
 * {@code laravel}, {@code symfony}, {@code lumen}, {@code cakephp}, {@code codeigniter}, {@code yii},
 * {@code slim}, or {@code none} for a site or library without any of them.
 */
public final class PhpFrameworkDetector implements DetectorFactory {

    @Override
    public String type() {
        return "phpFramework";
    }

    @Override
    public Detector create(Rule rule) {
        List<String> wanted = rule.detectParams().requiredStrings("is");
        return ctx -> {
            List<Finding> findings = new ArrayList<>();
            for (Module module : ctx.model().modules()) {
                String framework = String.valueOf(module.fact("framework"));
                String name = framework.split(" ")[0];
                if (wanted.contains(name)) {
                    findings.add(ctx.finding(rule, module.buildFile(), 0, name.equals("none") ? "no framework" : framework,
                            Map.of("framework", framework)));
                }
            }
            return findings;
        };
    }
}
