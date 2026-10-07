package io.renova.dotnet.detect;

import io.renova.core.model.Finding;
import io.renova.core.model.Module;
import io.renova.core.playbook.Rule;
import io.renova.core.spi.Detector;
import io.renova.core.spi.DetectorFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code type: assemblyReference, names: [System.Web, System.ServiceModel]} — one finding per project and
 * assembly it references by name, as projects in the pre-SDK format do for the parts of .NET Framework they use.
 */
public final class AssemblyReferenceDetector implements DetectorFactory {

    @Override
    public String type() {
        return "assemblyReference";
    }

    @Override
    public Detector create(Rule rule) {
        List<String> names = rule.detectParams().requiredStrings("names");
        return ctx -> {
            List<Finding> findings = new ArrayList<>();
            for (Module module : ctx.model().modules()) {
                if (!(module.fact("references") instanceof List<?> references)) {
                    continue;
                }
                for (Object reference : references) {
                    if (names.stream().anyMatch(n -> n.equalsIgnoreCase(reference.toString()))) {
                        findings.add(ctx.finding(rule, module.buildFile(), 0, reference.toString(),
                                Map.of("assembly", reference.toString())));
                    }
                }
            }
            return findings;
        };
    }
}
