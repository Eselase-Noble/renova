package io.renova.dotnet.detect;

import io.renova.core.model.Finding;
import io.renova.core.model.Module;
import io.renova.core.playbook.Rule;
import io.renova.core.spi.Detector;
import io.renova.core.spi.DetectorFactory;
import io.renova.dotnet.Tfm;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code type: referencesWindowsProject} — one finding per project on modern .NET that is not tied to Windows
 * and refers to a project that is ({@code net10.0-windows}: Windows Forms, WPF). NuGet refuses such a
 * reference, so the tests of a desktop application have to target Windows as it does.
 */
public final class WindowsReferenceDetector implements DetectorFactory {

    @Override
    public String type() {
        return "referencesWindowsProject";
    }

    @Override
    public Detector create(Rule rule) {
        return ctx -> {
            List<Finding> findings = new ArrayList<>();
            List<Module> modules = ctx.model().modules();
            for (Module module : modules) {
                if (windows(module) || !modern(module) || !(module.fact("projectReferences") instanceof List<?> references)) {
                    continue;
                }
                for (Object reference : references) {
                    String target = Path.of(module.path()).resolve(reference.toString()).normalize().toString().replace('\\', '/');
                    modules.stream().filter(m -> m.buildFile().equals(target) && windows(m)).findFirst().ifPresent(m ->
                            findings.add(ctx.finding(rule, module.buildFile(), 0, "refers to " + m.name() + ", which targets Windows")));
                }
            }
            return findings;
        };
    }

    public static boolean windows(Module module) {
        return module.fact("targetFrameworks") instanceof List<?> frameworks
                && frameworks.stream().anyMatch(t -> Tfm.platform(t.toString()).startsWith("-windows"));
    }

    private static boolean modern(Module module) {
        return module.fact("targetFrameworks") instanceof List<?> frameworks
                && frameworks.stream().anyMatch(t -> Tfm.kind(t.toString()) == Tfm.Kind.MODERN);
    }
}
