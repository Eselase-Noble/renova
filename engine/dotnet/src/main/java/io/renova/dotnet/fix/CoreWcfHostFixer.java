package io.renova.dotnet.fix;

import io.renova.core.engine.MigrationContext;
import io.renova.core.engine.PlanStep;
import io.renova.core.engine.StageResult;
import io.renova.core.playbook.Params;
import io.renova.core.spi.Fixer;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Fix strategy {@code corewcf}: a WCF service project that IIS hosted (.svc files and a
 * {@code system.serviceModel} section in Web.config) becomes an ASP.NET Core application that hosts the same
 * services with CoreWCF, at the addresses they had. Modern .NET has the WCF client and not the server; CoreWCF
 * is the server, under its own namespace.
 *
 * <p>The contracts and the service classes stay as they are except for their namespaces
 * ({@code System.ServiceModel} becomes {@code CoreWCF}). What is written new is the hosting: the project
 * file on the web SDK with the CoreWCF packages, and a Program.cs that registers each service with the
 * endpoints Web.config declared, or the one endpoint IIS gave a service without any (basicHttpBinding at the
 * address of its .svc file). The .svc files go; Web.config stays as App.config with the sections only IIS
 * read taken out, so that code reading settings with ConfigurationManager finds them.
 *
 * <p>Bindings other than basicHttpBinding, wsHttpBinding and webHttpBinding, behaviours and security settings
 * from the configuration are not carried over; each is named in the stage's details for a person.
 */
public final class CoreWcfHostFixer implements Fixer {

    public static final String STRATEGY = "corewcf";
    private static final Pattern SERVICE_HOST = Pattern.compile("<%@\\s*ServiceHost\\b([^%]*)%>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTRIBUTE = Pattern.compile("(\\w+)\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern NAMESPACE = Pattern.compile("(?m)^\\s*namespace\\s+([\\w.]+)");
    /** A class and what it is declared to extend or implement. */
    private static final Pattern CLASS = Pattern.compile("\\bclass\\s+(\\w+)\\s*(?::\\s*([^{]+?))?\\s*(?:where\\b[^{]*)?\\{");
    private static final Pattern CONTRACT = Pattern.compile("\\[\\s*(?:[\\w.]+\\.)?ServiceContract(?:Attribute)?\\b[^\\]]*]\\s*(?:\\[[^\\]]*]\\s*)*"
            + "(?:public|internal)?\\s*(?:partial\\s+)?interface\\s+(\\w+)");
    /** Code that calls other services keeps the client's namespace. */
    private static final Pattern CLIENT_CODE = Pattern.compile("\\b(ClientBase|ChannelFactory|DuplexChannelFactory)\\s*<");
    /** Sections of Web.config that IIS and System.Web read and nothing else does. */
    private static final List<String> WEB_ONLY_SECTIONS = List.of("system.web", "system.webServer", "system.serviceModel", "system.codedom",
            "runtime");

    @Override
    public String strategy() {
        return STRATEGY;
    }

    /** One endpoint of a service: where it answers and how. */
    record Endpoint(String address, String binding, String contract) {
    }

    /** A hosted service: the class, the contracts it implements, and its endpoints. */
    record Service(String type, List<Endpoint> endpoints) {
    }

    @Override
    public StageResult apply(MigrationContext context, List<PlanStep> steps) throws Exception {
        Path root = context.workspace().root();
        PlanStep step = steps.getFirst();
        Params params = step.rule().fix().params(step.rule().id());
        String version = params.optString("version").orElse("1.8.0");
        List<String> details = new ArrayList<>();
        int converted = 0;
        for (String file : step.files()) {
            String lower = file.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".csproj")) {
                if (lower.endsWith(".vbproj")) {
                    details.add(file + ": left as it is (only C# service projects are rewritten); see the rule's note");
                }
                continue;
            }
            Path project = root.resolve(file);
            List<String> notes = new ArrayList<>();
            if (convert(project, version, notes)) {
                converted++;
                // Projects built on this one (its tests) name the same types: a fault the service throws is CoreWCF's now.
                for (Path user : users(root, project)) {
                    int renamed = 0;
                    try (Stream<Path> sources = Files.walk(user.getParent())) {
                        for (Path source : sources.filter(f -> f.toString().endsWith(".cs")
                                && !io.renova.dotnet.Sources.produced(user.getParent().relativize(f))).toList()) {
                            String before = Files.readString(source, StandardCharsets.UTF_8);
                            String after = CLIENT_CODE.matcher(before).find() ? before : namespaces(before);
                            if (!after.equals(before)) {
                                Files.writeString(source, after, StandardCharsets.UTF_8);
                                renamed++;
                            }
                        }
                    }
                    if (renamed > 0) {
                        notes.add(root.relativize(user).toString().replace('\\', '/') + " uses this project: " + renamed
                                + " source file(s) there follow it to CoreWCF");
                    }
                }
            }
            notes.forEach(n -> details.add(file + ": " + n));
        }
        if (converted == 0) {
            return new StageResult(STRATEGY, StageResult.Status.SKIPPED, "no C# project with .svc files to host", details);
        }
        return new StageResult(STRATEGY, StageResult.Status.APPLIED, converted + " WCF service project(s) hosted on ASP.NET Core with CoreWCF "
                + version, details);
    }

    /** The other projects of the workspace that reference the project. */
    private static List<Path> users(Path root, Path project) throws IOException {
        String name = project.getFileName().toString();
        List<Path> users = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root, 8)) {
            for (Path file : files.filter(f -> f.toString().toLowerCase(Locale.ROOT).endsWith(".csproj") && !f.equals(project)
                    && !io.renova.dotnet.Sources.produced(root.relativize(f))).sorted().toList()) {
                if (Pattern.compile("<ProjectReference\\s+Include=\"[^\"]*" + Pattern.quote(name) + "\"").matcher(Files.readString(file)).find()) {
                    users.add(file);
                }
            }
        }
        return users;
    }

    static boolean convert(Path project, String version, List<String> notes) throws IOException {
        Path dir = project.getParent();
        Map<Path, String> sources = new LinkedHashMap<>();
        List<Path> svcFiles = new ArrayList<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (io.renova.dotnet.Sources.produced(dir.relativize(file))) {
                    continue;
                }
                if (name.endsWith(".cs")) {
                    sources.put(file, strip(Files.readString(file, StandardCharsets.UTF_8)));
                } else if (name.endsWith(".svc")) {
                    svcFiles.add(file);
                }
            }
        }
        if (svcFiles.isEmpty()) {
            return false;
        }
        Path webConfig = dir.resolve("Web.config");
        Document config = Files.isRegularFile(webConfig) ? parse(strip(Files.readString(webConfig, StandardCharsets.UTF_8))) : null;

        // The services: each .svc names a class; its endpoints are Web.config's, or the one IIS gave it.
        Map<String, String> contracts = contracts(sources);
        Map<String, List<String>> implemented = implemented(sources, contracts);
        Set<String> bindings = new LinkedHashSet<>();
        List<Service> services = new ArrayList<>();
        for (Path svc : svcFiles) {
            Matcher directive = SERVICE_HOST.matcher(Files.readString(svc, StandardCharsets.UTF_8));
            String type = null;
            if (directive.find()) {
                Matcher attribute = ATTRIBUTE.matcher(directive.group(1));
                while (attribute.find()) {
                    if (attribute.group(1).equalsIgnoreCase("Service")) {
                        type = attribute.group(2).split(",")[0].strip();
                    } else if (attribute.group(1).equalsIgnoreCase("Factory")) {
                        notes.add(dir.relativize(svc) + " names a host factory (" + attribute.group(2) + "): what it set up is not "
                                + "carried over and has to be done in Program.cs");
                    }
                }
            }
            String address = "/" + dir.relativize(svc).toString().replace('\\', '/');
            if (type == null || !implemented.containsKey(type)) {
                notes.add(address + ": the service class " + (type == null ? "is not named" : type + " was not found in the project")
                        + "; it is not hosted, and Program.cs says so");
                services.add(new Service(type == null ? address : type, List.of()));
                continue;
            }
            List<Endpoint> endpoints = new ArrayList<>();
            for (Endpoint declared : declared(config, type)) {
                String binding = declared.binding();
                if (binding.equalsIgnoreCase("mexHttpBinding") || binding.equalsIgnoreCase("mexHttpsBinding")
                        || declared.contract().equals("IMetadataExchange")) {
                    continue; // the metadata behaviour in Program.cs publishes the WSDL
                }
                if (!List.of("basichttpbinding", "basichttpsbinding", "wshttpbinding", "webhttpbinding").contains(binding.toLowerCase(Locale.ROOT))) {
                    notes.add(address + ": endpoint with " + binding + " is not hosted: add it in Program.cs with the CoreWCF package "
                            + "for that binding (CoreWCF.NetTcp, CoreWCF.NetNamedPipe), or replace it");
                    continue;
                }
                String contract = contracts.containsKey(declared.contract()) ? declared.contract()
                        : contracts.keySet().stream().filter(c -> c.endsWith("." + declared.contract())).findFirst().orElse(declared.contract());
                String relative = declared.address().isBlank() || declared.address().contains("://") ? "" : "/" + declared.address().replaceAll("^/+", "");
                endpoints.add(new Endpoint(address + relative, binding, contract));
                bindings.add(binding.toLowerCase(Locale.ROOT));
            }
            if (endpoints.isEmpty()) {
                for (String contract : implemented.get(type)) {
                    endpoints.add(new Endpoint(address, "basicHttpBinding", contract));
                    bindings.add("basichttpbinding");
                }
            }
            services.add(new Service(type, endpoints));
        }
        if (config != null) {
            settingsNotCarried(config, notes);
        }

        // Namespaces: the service side of WCF lives in CoreWCF.
        int renamed = 0;
        for (Map.Entry<Path, String> source : sources.entrySet()) {
            String after = CLIENT_CODE.matcher(source.getValue()).find() ? source.getValue() : namespaces(source.getValue());
            if (!after.equals(source.getValue())) {
                Files.writeString(source.getKey(), after, StandardCharsets.UTF_8);
                renamed++;
            } else if (CLIENT_CODE.matcher(after).find() && after.contains("System.ServiceModel")) {
                notes.add(dir.relativize(source.getKey()) + " calls another service (ClientBase or ChannelFactory): it keeps System.ServiceModel, "
                        + "the client, which is a package now");
            }
        }
        notes.add(renamed + " source file(s): System.ServiceModel → CoreWCF");

        // Hosting.
        Path program = dir.resolve("Program.cs");
        if (Files.exists(program)) {
            notes.add("Program.cs exists already and is left as it is; the services are not registered in it");
        } else {
            Files.writeString(program, program(services, bindings), StandardCharsets.UTF_8);
            long hosted = services.stream().filter(s -> !s.endpoints().isEmpty()).count();
            notes.add("Program.cs written: " + hosted + " service(s), " + services.stream().mapToInt(s -> s.endpoints().size()).sum()
                    + " endpoint(s), at the addresses the .svc files had");
        }
        for (Path svc : svcFiles) {
            Files.delete(svc);
        }
        notes.add(svcFiles.size() + " .svc file(s) removed: Program.cs maps their addresses");
        for (String leftover : List.of("Global.asax", "Global.asax.cs", "Web.Debug.config", "Web.Release.config")) {
            if (Files.deleteIfExists(dir.resolve(leftover))) {
                notes.add(leftover + " removed: " + (leftover.startsWith("Global") ? "what Application_Start did has to be done in Program.cs"
                        : "configuration transforms are replaced by appsettings.{Environment}.json or environment variables"));
            }
        }
        if (config != null) {
            String appConfig = appConfig(strip(Files.readString(webConfig, StandardCharsets.UTF_8)));
            Files.delete(webConfig);
            if (appConfig != null && !Files.exists(dir.resolve("App.config"))) {
                Files.writeString(dir.resolve("App.config"), appConfig, StandardCharsets.UTF_8);
                notes.add("Web.config → App.config, without the sections only IIS and System.Web read: appSettings and connectionStrings "
                        + "are still found by ConfigurationManager");
            } else {
                notes.add("Web.config removed: it held nothing but what IIS and System.Web read");
            }
        }

        // The project: the web SDK, CoreWCF's packages, and no items the SDK finds by itself.
        String xml = strip(Files.readString(project, StandardCharsets.UTF_8));
        Files.writeString(project, projectFile(xml, version, bindings), StandardCharsets.UTF_8);
        notes.add("project file: Microsoft.NET.Sdk.Web with CoreWCF.Primitives and CoreWCF.Http " + version
                + (bindings.contains("webhttpbinding") ? " and CoreWCF.WebHttp" : ""));
        return true;
    }

    /** Contract interfaces by full name, to the namespace-less name, from every [ServiceContract] interface in the sources. */
    static Map<String, String> contracts(Map<Path, String> sources) {
        Map<String, String> contracts = new LinkedHashMap<>();
        for (String code : sources.values()) {
            Matcher namespace = NAMESPACE.matcher(code);
            String prefix = namespace.find() ? namespace.group(1) + "." : "";
            Matcher contract = CONTRACT.matcher(code);
            while (contract.find()) {
                contracts.put(prefix + contract.group(1), contract.group(1));
            }
        }
        return contracts;
    }

    /** Classes by full name, to the full names of the service contracts they implement. */
    static Map<String, List<String>> implemented(Map<Path, String> sources, Map<String, String> contracts) {
        Map<String, List<String>> classes = new LinkedHashMap<>();
        for (String code : sources.values()) {
            Matcher namespace = NAMESPACE.matcher(code);
            String prefix = namespace.find() ? namespace.group(1) + "." : "";
            Matcher declared = CLASS.matcher(code);
            while (declared.find()) {
                List<String> implemented = new ArrayList<>();
                for (String base : declared.group(2) == null ? new String[0] : declared.group(2).split(",")) {
                    String name = base.strip().replaceAll("<.*", "");
                    String simple = name.substring(name.lastIndexOf('.') + 1);
                    contracts.entrySet().stream().filter(c -> c.getKey().equals(name) || c.getValue().equals(simple))
                            // An interface of the class's own namespace before one with the same name elsewhere.
                            .sorted(java.util.Comparator.comparing(c -> !c.getKey().equals(prefix + simple)))
                            .findFirst().ifPresent(c -> implemented.add(c.getKey()));
                }
                if (!implemented.isEmpty()) {
                    classes.put(prefix + declared.group(1), implemented);
                }
            }
        }
        return classes;
    }

    /** The endpoints Web.config declares for a service, as they are written there. */
    static List<Endpoint> declared(Document config, String serviceType) {
        List<Endpoint> endpoints = new ArrayList<>();
        if (config == null) {
            return endpoints;
        }
        NodeList services = config.getElementsByTagName("service");
        for (int i = 0; i < services.getLength(); i++) {
            Element service = (Element) services.item(i);
            if (!service.getAttribute("name").strip().equals(serviceType)) {
                continue;
            }
            NodeList declared = service.getElementsByTagName("endpoint");
            for (int j = 0; j < declared.getLength(); j++) {
                Element endpoint = (Element) declared.item(j);
                endpoints.add(new Endpoint(endpoint.getAttribute("address").strip(), endpoint.getAttribute("binding").strip(),
                        endpoint.getAttribute("contract").strip()));
            }
        }
        return endpoints;
    }

    /** What the configuration set that Program.cs does not: named for a person, not guessed at. */
    private static void settingsNotCarried(Document config, List<String> notes) {
        NodeList model = config.getElementsByTagName("system.serviceModel");
        if (model.getLength() == 0) {
            return;
        }
        Element serviceModel = (Element) model.item(0);
        List<String> found = new ArrayList<>();
        for (String[] setting : new String[][] {{"security", "security settings of a binding"}, {"serviceCredentials", "service credentials"},
                {"serviceAuthorization", "service authorization"}, {"serviceThrottling", "throttling"},
                {"serviceDebug", "includeExceptionDetailInFaults"}, {"client", "client endpoints (they are read from App.config by the "
                + "System.ServiceModel client only when given in code)"}, {"webHttp", "the webHttp endpoint behaviour"},
                {"readerQuotas", "reader quotas and message size limits"}}) {
            if (serviceModel.getElementsByTagName(setting[0]).getLength() > 0) {
                found.add(setting[1]);
            }
        }
        NodeList bindings = serviceModel.getElementsByTagName("binding");
        for (int i = 0; i < bindings.getLength(); i++) {
            Element binding = (Element) bindings.item(i);
            if (binding.getAttributes().getLength() > 1 || binding.hasChildNodes()) {
                found.add("the settings of binding \"" + binding.getAttribute("name") + "\" (sizes, timeouts, encoding)");
            }
        }
        if (!found.isEmpty()) {
            notes.add("to carry over by hand from system.serviceModel into Program.cs: " + String.join("; ", found));
        }
    }

    /** {@code System.ServiceModel} as CoreWCF names it, in a file of the service side. */
    static String namespaces(String code) {
        String result = code
                // IIS compatibility mode does not exist; the attribute and its namespace go.
                .replaceAll("(?m)^[ \\t]*\\[\\s*(?:System\\.ServiceModel\\.Activation\\.)?AspNetCompatibilityRequirements(?:Attribute)?\\s*\\([^\\]]*\\)\\s*][ \\t]*\\R", "")
                .replaceAll("(?m)^[ \\t]*using\\s+System\\.ServiceModel\\.Activation\\s*;[ \\t]*\\R", "")
                .replaceAll("\\bSystem\\.ServiceModel\\.Web\\b", "CoreWCF.Web")
                .replaceAll("\\bSystem\\.ServiceModel\\b", "CoreWCF");
        // Two usings of one namespace are a warning the old code did not have.
        Set<String> seen = new LinkedHashSet<>();
        StringBuilder out = new StringBuilder();
        for (String line : result.split("(?<=\\n)")) {
            String trimmed = line.strip();
            if (trimmed.matches("using\\s+[\\w.]+\\s*;") && !seen.add(trimmed.replaceAll("\\s+", " "))) {
                continue;
            }
            out.append(line);
        }
        return out.toString();
    }

    static String program(List<Service> services, Set<String> bindings) {
        StringBuilder code = new StringBuilder("""
                // Written by Renova: this project was a WCF service application hosted by IIS. It is an ASP.NET Core
                // application now, and CoreWCF hosts the same services at the addresses their .svc files had.
                using CoreWCF;
                using CoreWCF.Configuration;
                using CoreWCF.Description;
                using Microsoft.AspNetCore.Builder;
                using Microsoft.Extensions.DependencyInjection;

                var builder = WebApplication.CreateBuilder(args);
                builder.Services.AddServiceModelServices();
                builder.Services.AddServiceModelMetadata();
                """);
        if (bindings.contains("webhttpbinding")) {
            code.append("builder.Services.AddServiceModelWebServices();\n");
        }
        code.append("""
                // The WSDL names the address the client asked at, as it did behind IIS.
                builder.Services.AddSingleton<IServiceBehavior, UseRequestHeadersForMetadataAddressBehavior>();

                var app = builder.Build();
                app.UseServiceModel(services =>
                {
                """);
        for (Service service : services) {
            if (service.endpoints().isEmpty()) {
                code.append("    // Not hosted: ").append(service.type()).append(" (see the migration report).\n");
                continue;
            }
            code.append("    services.AddService<").append(service.type()).append(">();\n");
            for (Endpoint endpoint : service.endpoints()) {
                String binding = endpoint.binding().toLowerCase(Locale.ROOT);
                if (binding.equals("webhttpbinding")) {
                    code.append("    services.AddServiceWebEndpoint<").append(service.type()).append(", ").append(endpoint.contract())
                            .append(">(new WebHttpBinding(), \"").append(endpoint.address()).append("\");\n");
                } else {
                    String created = switch (binding) {
                        case "basichttpsbinding" -> "new BasicHttpBinding(BasicHttpSecurityMode.Transport)";
                        case "wshttpbinding" -> "new WSHttpBinding(SecurityMode.None)";
                        default -> "new BasicHttpBinding()";
                    };
                    code.append("    services.AddServiceEndpoint<").append(service.type()).append(", ").append(endpoint.contract())
                            .append(">(").append(created).append(", \"").append(endpoint.address()).append("\");\n");
                }
            }
        }
        code.append("""
                });

                // ?wsdl and ?singleWsdl at each service's address, for the tools that generate clients.
                var metadata = app.Services.GetRequiredService<ServiceMetadataBehavior>();
                metadata.HttpGetEnabled = true;

                app.Run();

                // So that tests can start the application.
                public partial class Program { }
                """);
        return code.toString();
    }

    /**
     * The project file on the web SDK with CoreWCF's packages. It may be in the old format still or converted
     * to the SDK style already; in the old format only the SDK and the packages are written, since the
     * conversion rule rewrites the rest.
     */
    static String projectFile(String xml, String version, Set<String> bindings) {
        List<String> packages = new ArrayList<>(List.of("CoreWCF.Primitives", "CoreWCF.Http"));
        if (bindings.contains("webhttpbinding")) {
            packages.add("CoreWCF.WebHttp");
        }
        String result = xml.replaceFirst("<Project\\s+Sdk=\"Microsoft\\.NET\\.Sdk\"", "<Project Sdk=\"Microsoft.NET.Sdk.Web\"");
        // Items the web SDK includes by itself, and files that are gone.
        result = result.replaceAll("(?m)^[ \\t]*<(Content|None)\\s+(Include|Update)=\"[^\"]*\\.(svc|config|json|asax)\"\\s*/>[ \\t]*\\R", "")
                .replaceAll("(?ms)^[ \\t]*<(Content|None)\\s+(Include|Update)=\"[^\"]*\\.(svc|config|json|asax)\"\\s*>.*?</\\1>[ \\t]*\\R", "")
                .replaceAll("(?ms)^[ \\t]*<Compile\\s+(Include|Update)=\"[^\"]*\\.(svc|asax)\\.cs\"\\s*>.*?</Compile>[ \\t]*\\R", "")
                .replaceAll("(?m)^[ \\t]*<PackageReference\\s+Include=\"(Microsoft\\.AspNet\\.[^\"]*|Microsoft\\.CodeDom\\.[^\"]*|Microsoft\\.Net\\.Compilers[^\"]*)\"[^>]*/>[ \\t]*\\R", "")
                .replaceAll("(?m)^[ \\t]*<ItemGroup>\\s*</ItemGroup>[ \\t]*\\R(?:[ \\t]*\\R)?", "");
        StringBuilder references = new StringBuilder("  <ItemGroup>\n");
        for (String id : packages) {
            if (!result.contains("Include=\"" + id + "\"")) {
                references.append("    <PackageReference Include=\"").append(id).append("\" Version=\"").append(version).append("\" />\n");
            }
        }
        references.append("  </ItemGroup>\n\n");
        int end = result.lastIndexOf("</Project>");
        return end < 0 ? result : result.substring(0, end) + references + result.substring(end);
    }

    /** Web.config as an App.config: what ConfigurationManager reads, without what only IIS and System.Web read; null if nothing is left. */
    static String appConfig(String webConfig) {
        String result = webConfig;
        for (String section : WEB_ONLY_SECTIONS) {
            String name = Pattern.quote(section);
            result = result.replaceAll("(?s)[ \\t]*<" + name + "(\\s[^>]*)?>.*?</" + name + ">[ \\t]*\\R?", "")
                    .replaceAll("[ \\t]*<" + name + "(\\s[^>]*)?/>[ \\t]*\\R?", "");
        }
        result = result.replaceAll("(?s)[ \\t]*<configSections>.*?</configSections>[ \\t]*\\R?", "");
        boolean anything = Pattern.compile("<(appSettings|connectionStrings)\\b[^>]*>\\s*<").matcher(result).find();
        return anything ? result : null;
    }

    private static Document parse(String xml) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder().parse(new org.xml.sax.InputSource(new StringReader(xml)));
        } catch (Exception e) {
            return null; // not XML a server could read either: the services get the endpoint IIS would have given them
        }
    }

    private static String strip(String text) {
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }
}
