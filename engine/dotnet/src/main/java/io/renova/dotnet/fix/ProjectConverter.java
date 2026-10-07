package io.renova.dotnet.fix;

import io.renova.dotnet.ProjectFile;
import io.renova.dotnet.Sources;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Rewrites a project file from the format before the .NET SDK into the SDK style, which is the only one the
 * {@code dotnet} tools build. The SDK style says less: source files are found by pattern instead of listed,
 * framework assemblies come with the target framework, and NuGet packages are named in the project file
 * instead of packages.config. What the old file said and the new format would not is written out, so the
 * project compiles the same files as before.
 */
public final class ProjectConverter {

    /** @param notes what a person may want to know, for the report */
    public record Converted(String xml, List<String> notes) {
    }

    /** Properties that mean the same in both formats and are carried over when the project sets them. */
    private static final List<String> CARRIED = List.of("RootNamespace", "AssemblyName", "StartupObject", "ApplicationIcon",
            "ApplicationManifest", "AllowUnsafeBlocks", "LangVersion", "SignAssembly", "AssemblyOriginatorKeyFile", "DelaySign",
            "OptionStrict", "OptionExplicit", "OptionInfer", "OptionCompare", "Platforms");
    /** Items Visual Studio kept for itself; they have no meaning in the SDK style. */
    private static final Set<String> DROPPED_ITEMS = Set.of("Folder", "Service", "BootstrapperPackage", "Analyzer", "WCFMetadata",
            "CodeAnalysisDictionary");

    private ProjectConverter() {
    }

    /**
     * @param assemblyPackages framework assemblies (lower case) that modern .NET ships as NuGet packages,
     *                         each to "id:version"
     */
    public static Converted convert(ProjectFile project, Map<String, String> assemblyPackages) throws IOException {
        Path dir = project.file().toAbsolutePath().getParent();
        String extension = project.visualBasic() ? ".vb" : ".cs";
        List<String> notes = new ArrayList<>();
        String kind = project.kind();

        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("TargetFramework", project.targetFrameworks().isEmpty() ? "net48" : project.targetFrameworks().getFirst());
        if (!"Library".equalsIgnoreCase(project.outputType())) {
            properties.put("OutputType", project.outputType());
        }
        for (String name : CARRIED) {
            String value = project.properties().get(name);
            boolean isDefault = (name.equals("RootNamespace") || name.equals("AssemblyName")) && project.name().equals(value);
            if (value != null && !value.isBlank() && !isDefault) {
                properties.put(name, value);
            }
        }
        // Visual Basic's My namespace for desktop applications (My.Computer, My.Forms) exists with Windows
        // Forms only; any other project gets the SDK's default, which leaves those parts out.
        if (project.properties().containsKey("MyType") && (kind.equals("winforms") || kind.equals("wpf"))) {
            properties.put("MyType", project.properties().get("MyType"));
        }
        if (kind.equals("wpf")) {
            properties.put("UseWPF", "true");
        }
        if (kind.equals("winforms") || (kind.equals("wpf") && project.references("System.Windows.Forms"))) {
            properties.put("UseWindowsForms", "true");
        }

        // Source files: the old format lists them, the SDK style takes every file under the project folder.
        Set<String> compiled = new LinkedHashSet<>();
        List<String> items = new ArrayList<>();
        for (ProjectFile.Item item : project.items()) {
            String include = item.include().replace('\\', '/');
            Map<String, String> metadata = new LinkedHashMap<>(item.metadata());
            metadata.remove("SubType");
            switch (item.type()) {
                case "Compile" -> {
                    compiled.add(include.toLowerCase(Locale.ROOT));
                    if (include.toLowerCase(Locale.ROOT).endsWith("assemblyinfo" + extension)) {
                        // The file already declares what the SDK would otherwise generate a second time.
                        properties.put("GenerateAssemblyInfo", "false");
                    }
                    if (include.startsWith("../") || metadata.containsKey("Link")) {
                        items.add(item("Compile", "Include", item.include(), metadata));
                    } else if (!metadata.isEmpty()) {
                        items.add(item("Compile", "Update", item.include(), metadata));
                    }
                }
                case "EmbeddedResource" -> {
                    boolean found = include.toLowerCase(Locale.ROOT).endsWith(".resx") && !include.startsWith("../");
                    if (!found || !metadata.isEmpty()) {
                        items.add(item("EmbeddedResource", found ? "Update" : "Include", item.include(), metadata));
                    }
                }
                case "None" -> {
                    String name = include.substring(include.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
                    if (!name.equals("packages.config") && !metadata.isEmpty()) {
                        items.add(item("None", include.startsWith("../") ? "Include" : "Update", item.include(), metadata));
                    }
                }
                default -> {
                    if (!DROPPED_ITEMS.contains(item.type())) {
                        items.add(item(item.type(), "Include", item.include(), metadata));
                    }
                }
            }
        }
        List<String> removed = unlistedSources(dir, extension, compiled);
        removed.forEach(r -> items.add(0, "    <Compile Remove=\"" + r.replace('/', '\\') + "\" />"));
        if (!removed.isEmpty()) {
            notes.add(removed.size() + " source file(s) or folder(s) on disk were not part of the project and are kept out of it: "
                    + String.join(", ", removed.subList(0, Math.min(5, removed.size()))) + (removed.size() > 5 ? ", …" : ""));
        }

        // Packages: packages.config and the project's own, then the framework assemblies that became packages.
        Map<String, String> packages = new LinkedHashMap<>();
        project.packages().forEach(p -> packages.putIfAbsent(p.id(), p.version()));
        List<String> references = new ArrayList<>();
        for (ProjectFile.Reference reference : project.references()) {
            String hint = reference.hintPath() == null ? null : reference.hintPath().replace('\\', '/');
            if (hint != null && !hint.contains("packages/")) {
                references.add("    <Reference Include=\"" + escape(reference.name()) + "\">\n      <HintPath>"
                        + escape(reference.hintPath()) + "</HintPath>\n    </Reference>");
                notes.add(reference.name() + " is a file in the project (" + hint + "): it must be an assembly modern .NET can load");
                continue;
            }
            String replacement = hint == null ? assemblyPackages.get(reference.name().toLowerCase(Locale.ROOT)) : null;
            if (replacement != null) {
                for (String one : replacement.split("\\s*,\\s*")) {
                    String[] idVersion = one.split(":", 2);
                    if (packages.keySet().stream().noneMatch(id -> id.equalsIgnoreCase(idVersion[0]))) {
                        packages.put(idVersion[0], idVersion.length > 1 ? idVersion[1] : null);
                    }
                }
            }
        }

        StringBuilder xml = new StringBuilder("<Project Sdk=\"Microsoft.NET.Sdk\">\n\n  <PropertyGroup>\n");
        properties.forEach((name, value) -> xml.append("    <").append(name).append('>').append(escape(value))
                .append("</").append(name).append(">\n"));
        xml.append("  </PropertyGroup>\n");
        if (!packages.isEmpty()) {
            xml.append("\n  <ItemGroup>\n");
            packages.forEach((id, version) -> xml.append("    <PackageReference Include=\"").append(escape(id)).append('"')
                    .append(version == null ? "" : " Version=\"" + escape(version) + "\"").append(" />\n"));
            xml.append("  </ItemGroup>\n");
        }
        if (!project.projectReferences().isEmpty()) {
            xml.append("\n  <ItemGroup>\n");
            project.projectReferences().forEach(r -> xml.append("    <ProjectReference Include=\"")
                    .append(escape(r.replace('/', '\\'))).append("\" />\n"));
            xml.append("  </ItemGroup>\n");
        }
        if (!references.isEmpty()) {
            xml.append("\n  <ItemGroup>\n").append(String.join("\n", references)).append("\n  </ItemGroup>\n");
        }
        if (!items.isEmpty()) {
            xml.append("\n  <ItemGroup>\n").append(String.join("\n", items)).append("\n  </ItemGroup>\n");
        }
        return new Converted(xml.append("\n</Project>\n").toString(), notes);
    }

    /**
     * Source files under the project folder that the old project did not compile. Another project's folder
     * inside this one is left out whole; elsewhere each file is named.
     */
    private static List<String> unlistedSources(Path dir, String extension, Set<String> compiled) throws IOException {
        List<String> removed = new ArrayList<>();
        Set<Path> nested = new LinkedHashSet<>();
        List<Path> sources = new ArrayList<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                Path relative = dir.relativize(file);
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (Sources.produced(relative)) {
                    continue;
                }
                if ((name.endsWith(".csproj") || name.endsWith(".vbproj")) && relative.getNameCount() > 1) {
                    nested.add(relative.getParent());
                } else if (name.endsWith(extension)) {
                    sources.add(relative);
                }
            }
        }
        nested.forEach(n -> removed.add(n.toString().replace('\\', '/') + "/**"));
        for (Path source : sources) {
            String path = source.toString().replace('\\', '/');
            if (nested.stream().noneMatch(source::startsWith) && !compiled.contains(path.toLowerCase(Locale.ROOT))) {
                removed.add(path);
            }
        }
        return removed;
    }

    private static String item(String type, String verb, String include, Map<String, String> metadata) {
        StringBuilder xml = new StringBuilder("    <").append(type).append(' ').append(verb).append("=\"").append(escape(include)).append('"');
        if (metadata.isEmpty()) {
            return xml.append(" />").toString();
        }
        xml.append(">\n");
        metadata.forEach((name, value) -> xml.append("      <").append(name).append('>').append(escape(value))
                .append("</").append(name).append(">\n"));
        return xml.append("    </").append(type).append('>').toString();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
