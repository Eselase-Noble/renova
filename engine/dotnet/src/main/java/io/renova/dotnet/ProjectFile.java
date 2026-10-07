package io.renova.dotnet;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a C# or Visual Basic project file (.csproj, .vbproj) declares, in either format: the SDK style
 * ({@code <Project Sdk="Microsoft.NET.Sdk">}) or the older one that lists every source file and imports
 * Microsoft.CSharp.targets, with its NuGet packages in a packages.config beside it.
 *
 * @param sdk              the SDK of an SDK-style project, null for the older format
 * @param targetFrameworks monikers such as net48, netcoreapp3.1 or net8.0; several for a multi-targeted project
 * @param outputType       Library, Exe or WinExe
 * @param properties       every property of the unconditional property groups, by name
 * @param packages         NuGet packages, from PackageReference items and packages.config
 * @param references       assembly references of the older format: framework assemblies and files
 * @param items            every other item (Compile, Content, None, EmbeddedResource, ...), in file order
 */
public record ProjectFile(Path file, String sdk, List<String> targetFrameworks, String outputType,
                          Map<String, String> properties, List<Package> packages, List<Reference> references,
                          List<String> projectReferences, List<Item> items) {

    public record Package(String id, String version) {
        public String coordinates() {
            return version == null ? id : id + ":" + version;
        }
    }

    /** @param name the assembly's simple name; @param hintPath where the file is, or null for a framework assembly */
    public record Reference(String name, String hintPath) {
    }

    /** @param type the item's element name; @param metadata its child elements, e.g. CopyToOutputDirectory */
    public record Item(String type, String include, Map<String, String> metadata) {
    }

    /** Project types Visual Studio marks with a GUID: classic ASP.NET web applications. */
    private static final String WEB_APPLICATION_GUID = "349c5851-65df-11da-9384-00065b846f21";
    private static final List<String> TEST_PACKAGES = List.of("xunit", "nunit", "mstest.testframework", "mstest",
            "microsoft.net.test.sdk");

    public boolean sdkStyle() {
        return sdk != null;
    }

    public boolean visualBasic() {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".vbproj");
    }

    public String name() {
        String fileName = file.getFileName().toString();
        return fileName.substring(0, fileName.lastIndexOf('.'));
    }

    public boolean hasPackage(String id) {
        return packages.stream().anyMatch(p -> p.id().equalsIgnoreCase(id));
    }

    public boolean references(String assembly) {
        return references.stream().anyMatch(r -> r.name().equalsIgnoreCase(assembly));
    }

    public boolean test() {
        return packages.stream().anyMatch(p -> TEST_PACKAGES.contains(p.id().toLowerCase(Locale.ROOT)))
                || references("Microsoft.VisualStudio.QualityTools.UnitTestFramework") || references("nunit.framework")
                || references("xunit") || references("xunit.core");
    }

    /**
     * What the project is: {@code aspnet} (classic ASP.NET on System.Web: Web Forms, MVC 5, Web API 2, WCF
     * hosted in IIS), {@code web} (ASP.NET Core), {@code wpf}, {@code winforms}, {@code test}, {@code exe} or
     * {@code library}.
     */
    public String kind() {
        String guids = properties.getOrDefault("ProjectTypeGuids", "").toLowerCase(Locale.ROOT);
        if (guids.contains(WEB_APPLICATION_GUID) || (!sdkStyle() && references("System.Web"))) {
            return "aspnet";
        }
        if ("Microsoft.NET.Sdk.Web".equalsIgnoreCase(sdk)) {
            return "web";
        }
        if (references("PresentationFramework") || "true".equalsIgnoreCase(properties.get("UseWPF"))) {
            return "wpf";
        }
        if (references("System.Windows.Forms") || "true".equalsIgnoreCase(properties.get("UseWindowsForms"))) {
            return "winforms";
        }
        if (test()) {
            return "test";
        }
        return outputType != null && outputType.toLowerCase(Locale.ROOT).contains("exe") ? "exe" : "library";
    }

    public static ProjectFile read(Path file) throws IOException {
        Document doc;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setNamespaceAware(true);
            try (var in = Files.newInputStream(file)) {
                doc = factory.newDocumentBuilder().parse(in);
            }
        } catch (javax.xml.parsers.ParserConfigurationException | org.xml.sax.SAXException e) {
            throw new IOException("Cannot read project file " + file + ": " + e.getMessage(), e);
        }
        Element project = doc.getDocumentElement();
        String sdk = project.hasAttribute("Sdk") ? project.getAttribute("Sdk") : null;
        Map<String, String> properties = new LinkedHashMap<>();
        List<Package> packages = new ArrayList<>();
        List<Reference> references = new ArrayList<>();
        List<String> projectReferences = new ArrayList<>();
        List<Item> items = new ArrayList<>();
        for (Element group : children(project)) {
            if (group.getLocalName().equals("Sdk") && sdk == null) {
                sdk = group.getAttribute("Name");
            } else if (group.getLocalName().equals("PropertyGroup")) {
                // Conditional groups hold what differs between Debug and Release; the unconditional ones say
                // what the project is. A default written as Condition=" '$(X)' == '' " still counts.
                for (Element property : children(group)) {
                    boolean isDefault = property.getAttribute("Condition").contains("== ''");
                    if (group.hasAttribute("Condition") || (property.hasAttribute("Condition") && !isDefault)) {
                        continue;
                    }
                    properties.put(property.getLocalName(), property.getTextContent().strip());
                }
            } else if (group.getLocalName().equals("ItemGroup")) {
                for (Element item : children(group)) {
                    String include = item.hasAttribute("Include") ? item.getAttribute("Include") : item.getAttribute("Update");
                    Map<String, String> metadata = new LinkedHashMap<>();
                    children(item).forEach(m -> metadata.put(m.getLocalName(), m.getTextContent().strip()));
                    switch (item.getLocalName()) {
                        case "PackageReference" -> packages.add(new Package(include, item.hasAttribute("Version")
                                ? item.getAttribute("Version") : metadata.get("Version")));
                        case "Reference" -> references.add(new Reference(include.split(",")[0].strip(), metadata.get("HintPath")));
                        case "ProjectReference" -> projectReferences.add(include.replace('\\', '/'));
                        default -> items.add(new Item(item.getLocalName(), include, metadata));
                    }
                }
            }
        }
        packages.addAll(packagesConfig(file.resolveSibling("packages.config"), packages));

        List<String> frameworks = new ArrayList<>();
        String declared = properties.getOrDefault("TargetFrameworks", properties.get("TargetFramework"));
        if (declared != null) {
            for (String tfm : declared.split(";")) {
                if (!tfm.isBlank()) {
                    frameworks.add(tfm.strip());
                }
            }
        } else if (properties.containsKey("TargetFrameworkVersion")) {
            frameworks.add(Tfm.fromFrameworkVersion(properties.get("TargetFrameworkVersion")));
        } else if (sdk == null) {
            frameworks.add("net40"); // what an old project without the property was built for
        }
        return new ProjectFile(file, sdk, List.copyOf(frameworks), properties.getOrDefault("OutputType", "Library"),
                properties, List.copyOf(packages), List.copyOf(references), List.copyOf(projectReferences), List.copyOf(items));
    }

    /** The packages of a packages.config that the project file does not already name. */
    private static List<Package> packagesConfig(Path config, List<Package> known) throws IOException {
        if (!Files.isRegularFile(config)) {
            return List.of();
        }
        List<Package> packages = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<package\\s+[^>]*?id=\"([^\"]+)\"[^>]*?version=\"([^\"]+)\"")
                .matcher(Files.readString(config));
        while (m.find()) {
            String id = m.group(1);
            if (known.stream().noneMatch(p -> p.id().equalsIgnoreCase(id))) {
                packages.add(new Package(id, m.group(2)));
            }
        }
        return packages;
    }

    private static List<Element> children(Element parent) {
        List<Element> elements = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i).getNodeType() == Node.ELEMENT_NODE) {
                elements.add((Element) nodes.item(i));
            }
        }
        return elements;
    }
}
