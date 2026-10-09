package io.renova.dotnet.fix;

import io.renova.core.engine.MigrationContext;
import io.renova.core.engine.PlanStep;
import io.renova.core.engine.StageResult;
import io.renova.core.model.Finding;
import io.renova.core.playbook.Params;
import io.renova.core.spi.Fixer;
import io.renova.dotnet.ProjectFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fix strategy {@code dotnet}: changes to project files (.csproj, .vbproj), driven by the rule's
 * {@code fix.params}.
 *
 * <ul>
 *   <li>{@code action: convertToSdkStyle, assemblies: [{assembly: System.Configuration, package: "Id:1.0"}]}
 *       rewrites a project in the pre-SDK format (see {@link ProjectConverter}) and removes its
 *       packages.config; {@code assemblies} names the parts of .NET Framework that are packages now.</li>
 *   <li>{@code action: setTargetFramework, version: "10.0"} moves the project to modern .NET.</li>
 *   <li>{@code action: setPackageVersion, version: "x"} sets the version of the packages the rule found.</li>
 *   <li>{@code action: addPackage, packages: ["Id:1.0"]} adds packages a project does not have yet. In a version,
 *       <code>${target}</code> is the .NET release the playbook moves to: {@code "Id:${target}.*"} is {@code 10.0.*}.</li>
 *   <li>{@code action: replacePackage, packages: ["New:1.0"]} removes the packages the rule found and adds these.</li>
 *   <li>{@code action: removePackage} removes the packages the rule found.</li>
 *   <li>{@code action: setProperty, name: X, value: y} sets an MSBuild property.</li>
 *   <li>{@code action: targetWindows} ties the project's target framework to Windows ({@code net10.0-windows}).</li>
 * </ul>
 */
public final class DotnetProjectFixer implements Fixer {

    public static final String STRATEGY = "dotnet";
    private static final List<String> ACTIONS = List.of("convertToSdkStyle", "setTargetFramework", "setPackageVersion", "addPackage",
            "replacePackage", "removePackage", "setProperty", "targetWindows");

    @Override
    public String strategy() {
        return STRATEGY;
    }

    @Override
    public StageResult apply(MigrationContext context, List<PlanStep> steps) throws Exception {
        Path root = context.workspace().root();
        List<String> details = new ArrayList<>();
        int changed = 0;
        // Every other edit is made to an SDK-style file, so conversions come first whatever the plan's order.
        List<PlanStep> ordered = new ArrayList<>(steps);
        ordered.sort(java.util.Comparator.comparing(step -> !"convertToSdkStyle".equals(step.rule().fix().params().get("action"))));
        for (PlanStep step : ordered) {
            String ruleId = step.rule().id();
            Params params = step.rule().fix().params(ruleId);
            String action = params.string("action");
            if (!ACTIONS.contains(action)) {
                throw new IllegalArgumentException("Rule '" + ruleId + "': unknown dotnet action '" + action + "'; use one of " + ACTIONS);
            }
            for (String file : step.files()) {
                String lower = file.toLowerCase(Locale.ROOT);
                if (!lower.endsWith(".csproj") && !lower.endsWith(".vbproj")) {
                    continue;
                }
                Path path = root.resolve(file);
                String before = Files.readString(path, StandardCharsets.UTF_8);
                String content = before.startsWith("\uFEFF") ? before.substring(1) : before;
                boolean sdkStyle = content.matches("(?s).*<Project\\b[^>]*\\bSdk=.*") || content.contains("<Sdk ");
                String note = null;
                if (action.equals("convertToSdkStyle")) {
                    if (sdkStyle) {
                        note = "already in the SDK style";
                    } else {
                        ProjectConverter.Converted converted = ProjectConverter.convert(ProjectFile.read(path), assemblies(params));
                        content = before.contains("\r\n") ? converted.xml().replace("\n", "\r\n") : converted.xml();
                        Files.deleteIfExists(path.resolveSibling("packages.config"));
                        converted.notes().forEach(n -> details.add(ruleId + ": " + file + ": " + n));
                    }
                } else if (!sdkStyle) {
                    note = "left as it is: not in the SDK style";
                } else {
                    content = edit(content, action, params, step, file, context.playbook().targets().getOrDefault("dotnet", "10.0"));
                }
                if (!content.equals(before)) {
                    Files.writeString(path, content, StandardCharsets.UTF_8);
                    changed++;
                }
                details.add(ruleId + ": " + file + ": " + (note != null ? note : content.equals(before) ? "already correct" : action));
            }
        }
        return new StageResult(STRATEGY, StageResult.Status.APPLIED, changed + " project file edit(s) by " + steps.size() + " rule(s)", details);
    }

    /** @param target the .NET release the playbook moves to ("10.0"), which <code>${target}</code> in a package version stands for */
    private static String edit(String xml, String action, Params params, PlanStep step, String file, String target) {
        List<String> found = step.findings().stream().filter(f -> f.file().equals(file) && f.data().containsKey("package"))
                .map(f -> f.data().get("package")).distinct().toList();
        switch (action) {
            case "setTargetFramework" -> xml = ProjectXml.setTargetFramework(xml, params.string("version"));
            case "setPackageVersion" -> {
                for (String id : found) {
                    xml = ProjectXml.setPackageVersion(xml, id, params.string("version"));
                }
            }
            case "removePackage", "replacePackage" -> {
                for (String id : found) {
                    xml = ProjectXml.removePackage(xml, id);
                }
            }
            case "setProperty" -> xml = ProjectXml.setProperty(xml, params.string("name"), params.string("value"));
            case "targetWindows" -> xml = ProjectXml.targetWindows(xml);
            default -> { }
        }
        if (action.equals("addPackage") || action.equals("replacePackage")) {
            for (String one : params.requiredStrings("packages")) {
                String[] idVersion = one.split(":", 2);
                xml = ProjectXml.addPackage(xml, idVersion[0], idVersion.length > 1 ? idVersion[1].replace("${target}", target) : null);
            }
        }
        return xml;
    }

    private static Map<String, String> assemblies(Params params) {
        Map<String, String> packages = new LinkedHashMap<>();
        for (Map<String, Object> entry : params.maps("assemblies")) {
            packages.put(String.valueOf(entry.get("assembly")).toLowerCase(Locale.ROOT), String.valueOf(entry.get("package")));
        }
        return packages;
    }
}
