package io.renova.java.fix;

import io.renova.core.engine.MigrationContext;
import io.renova.core.engine.PlanStep;
import io.renova.core.engine.StageResult;
import io.renova.core.spi.Fixer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Moves a Jakarta EE application off its application server and onto Spring Boot ({@code strategy: replatform}).
 * It runs after the javax → jakarta recipes, on code that already imports {@code jakarta.*}.
 *
 * <p>What it keeps: the standard APIs Spring Boot runs as they are. REST resources stay JAX-RS and run on
 * Jersey; entities and {@code EntityManager} stay JPA; Bean Validation, {@code @Inject}, {@code @PostConstruct}
 * and {@code jakarta.transaction.Transactional} are understood by Spring. What it changes: what only an
 * application server provides. Session beans and CDI scopes become Spring components (session beans
 * transactional, as the container made them), the server's descriptors become {@code application.properties},
 * and the build gets Spring Boot's starters in place of the platform API the server supplied.
 */
public final class SpringBootReplatformer implements Fixer {

    public static final String STRATEGY = "replatform";
    static final String DEFAULT_BOOT = "3.5.7";

    private static final String ARGS = "(\\s*\\((?:[^()\"]|\"(?:[^\"\\\\]|\\\\.)*\"|\\((?:[^()\"]|\"(?:[^\"\\\\]|\\\\.)*\")*\\))*\\))?";
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern TYPE = Pattern.compile("(?m)^(?:public\\s+|abstract\\s+|final\\s+)*(class|interface|enum|record|@interface)\\s+(\\w+)");
    private static final Pattern COMPONENT_API = Pattern.compile("(?m)^import\\s+jakarta\\.(ejb|enterprise|ws\\.rs|persistence|servlet|inject|validation|faces|jms)\\b");

    @Override
    public String strategy() {
        return STRATEGY;
    }

    @Override
    public StageResult apply(MigrationContext context, List<PlanStep> steps) throws Exception {
        String boot = steps.stream().map(s -> s.rule().fix().params(s.rule().id()).optString("bootVersion").orElse(null))
                .filter(v -> v != null).findFirst().orElse(DEFAULT_BOOT);
        Path root = context.workspace().root();
        List<String> details = new ArrayList<>();
        int converted = 0;
        for (io.renova.core.model.Module module : context.project().modules()) {
            Path dir = root.resolve(module.path()).normalize();
            boolean built = "maven".equals(module.fact("buildTool")) || "gradle".equals(module.fact("buildTool"));
            if (!built || !Files.isDirectory(dir.resolve("src/main/java"))) {
                continue;
            }
            String label = module.path().equals(".") ? "" : module.path() + ": ";
            List<String> notes = new ArrayList<>();
            if (convert(dir, boot, notes)) {
                converted++;
            }
            notes.forEach(n -> details.add(label + n));
        }
        if (converted == 0) {
            return new StageResult(STRATEGY, StageResult.Status.SKIPPED, "no Maven or Gradle module with Jakarta EE components found", details);
        }
        return new StageResult(STRATEGY, StageResult.Status.APPLIED, converted + " module(s) moved to Spring Boot " + boot, details);
    }

    /** What the module uses, read from its imports. */
    record Uses(boolean rest, boolean jpa, boolean validation, boolean ejb, boolean inject, boolean servletComponents,
                boolean async, boolean scheduled, boolean jaxb) {
    }

    static boolean convert(Path module, String boot, List<String> notes) throws IOException {
        Path sources = module.resolve("src/main/java");
        Map<Path, String> files = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(sources)) {
            for (Path file : walk.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java")).sorted().toList()) {
                files.put(file, Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        String all = String.join("\n", files.values());
        if (!COMPONENT_API.matcher(all).find() || all.contains("@SpringBootApplication")) {
            return false;
        }
        Uses uses = new Uses(imports(all, "jakarta.ws.rs"), imports(all, "jakarta.persistence"), imports(all, "jakarta.validation"),
                imports(all, "jakarta.ejb"), imports(all, "jakarta.inject"),
                Pattern.compile("@Web(Servlet|Filter|Listener)\\b").matcher(all).find(),
                all.contains("jakarta.ejb.Asynchronous"), all.contains("jakarta.ejb.Schedule"), imports(all, "jakarta.xml.bind"));

        // Components: session beans, scopes, injection.
        List<String> resources = new ArrayList<>();
        Path restApplication = null;
        String applicationPath = null;
        int changed = 0;
        boolean singleUnit = persistenceUnits(module) <= 1;
        for (Map.Entry<Path, String> entry : files.entrySet()) {
            String before = entry.getValue();
            String after = components(before, singleUnit);
            String type = typeName(after);
            boolean classLevelPath = type != null && classAnnotations(after).contains("@Path");
            boolean provider = type != null && classAnnotations(after).contains("@Provider");
            if (after.contains("extends Application") && imports(after, "jakarta.ws.rs")) {
                Matcher path = Pattern.compile("@ApplicationPath\\(\\s*\"([^\"]*)\"\\s*\\)").matcher(after);
                applicationPath = path.find() ? path.group(1) : null;
                if (Pattern.compile("extends\\s+Application\\s*\\{\\s*}").matcher(after).find()) {
                    restApplication = entry.getKey();
                } else {
                    notes.add(sources.relativize(entry.getKey()) + " configures JAX-RS in code; it is left as it is: register what it "
                            + "registers in the generated JerseyConfig, then delete it");
                }
            } else if ((classLevelPath || provider) && type != null && !TYPE.matcher(after).results()
                    .findFirst().map(m -> m.group(1)).orElse("").equals("interface")) {
                resources.add(qualified(after, type));
                if (!hasStereotype(classAnnotations(after))) {
                    after = annotateType(after, "@Component", "org.springframework.stereotype.Component");
                }
            }
            if (!after.equals(before)) {
                Files.writeString(entry.getKey(), after, StandardCharsets.UTF_8);
                entry.setValue(after);
                changed++;
            }
        }
        notes.add(changed + " class(es): session beans and CDI scopes are Spring components, session beans transactional");

        String base = basePackage(files.values());
        Path baseDir = base.isEmpty() ? sources : sources.resolve(base.replace('.', '/'));
        Files.createDirectories(baseDir);
        String pkg = base.isEmpty() ? "" : "package " + base + ";\n\n";

        // REST: JAX-RS resources run on Jersey, registered one by one (scanning does not see inside an executable jar).
        if (uses.rest() && !resources.isEmpty()) {
            StringBuilder registrations = new StringBuilder();
            resources.forEach(r -> registrations.append("        register(").append(r).append(".class);\n"));
            String name = restApplication != null ? typeName(files.get(restApplication)) : "JerseyConfig";
            Path file = restApplication != null ? restApplication : baseDir.resolve("JerseyConfig.java");
            String filePackage = restApplication != null ? packageOf(files.get(restApplication)) : base;
            String source = (filePackage.isEmpty() ? "" : "package " + filePackage + ";\n\n")
                    + (applicationPath == null ? "" : "import jakarta.ws.rs.ApplicationPath;\n")
                    + "import org.glassfish.jersey.server.ResourceConfig;\nimport org.springframework.stereotype.Component;\n\n"
                    + "/** The REST resources Jersey serves. A new resource class is registered here. */\n@Component\n"
                    + (applicationPath == null ? "" : "@ApplicationPath(\"" + applicationPath + "\")\n")
                    + "public class " + name + " extends ResourceConfig {\n\n    public " + name + "() {\n" + registrations + "    }\n}\n";
            Files.writeString(file, source, StandardCharsets.UTF_8);
            notes.add(sources.relativize(file) + " registers " + resources.size() + " JAX-RS resource(s) with Jersey"
                    + (applicationPath == null ? "" : " under " + applicationPath));
        }

        // The descriptors the server read.
        boolean war = Files.isDirectory(module.resolve("src/main/webapp"));
        Properties properties = new Properties();
        // The build: a pom.xml, or a Gradle build script in either language.
        Path buildFile = Stream.of("pom.xml", "build.gradle", "build.gradle.kts").map(module::resolve).filter(Files::isRegularFile)
                .findFirst().orElseThrow(() -> new IOException("no build file in " + module));
        boolean maven = buildFile.getFileName().toString().equals("pom.xml");
        String pom = Files.readString(buildFile, StandardCharsets.UTF_8);
        String contextRoot = descriptors(module, properties, notes);
        if (contextRoot == null && maven && Pattern.compile("<packaging>\\s*war\\s*</packaging>").matcher(pom).find()) {
            // The server published a WAR under its file name.
            String artifact = xml(pom.replaceAll("(?s)<parent>.*?</parent>", ""), "artifactId");
            String finalName = xml(pom, "finalName");
            contextRoot = "/" + (finalName != null && !finalName.contains("${") ? finalName : artifact);
        }
        if (contextRoot == null && !maven && GRADLE_WAR.matcher(pom).find()) {
            contextRoot = "/" + gradleArchiveName(module, pom);
        }
        if (contextRoot != null && !contextRoot.equals("/")) {
            properties.put("server.servlet.context-path", contextRoot, "The address the application server published it under.");
        }
        // web.xml: an embedded server does not read it, so what it declares is declared in code.
        Path webXml = module.resolve("src/main/webapp/WEB-INF/web.xml");
        if (Files.isRegularFile(webXml)) {
            String configuration = WebXml.configuration(Files.readString(webXml, StandardCharsets.UTF_8), base, properties, notes);
            if (configuration != null) {
                Files.writeString(baseDir.resolve("WebConfiguration.java"), configuration, StandardCharsets.UTF_8);
            }
            Files.delete(webXml);
            notes.add("WEB-INF/web.xml → " + (configuration == null ? "" : sources.relativize(baseDir.resolve("WebConfiguration.java")) + " and ")
                    + "application.properties: an embedded server does not read web.xml");
        }
        boolean webContent = war && hasWebContent(module.resolve("src/main/webapp"));
        if (war && !webContent) {
            deleteEmpty(module.resolve("src/main/webapp"));
        }
        if (uses.jpa()) {
            properties.comment("spring.datasource.url", "The database, which the application server used to supply. Set these, or the "
                    + "environment variables SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME and SPRING_DATASOURCE_PASSWORD.");
            properties.comment("spring.datasource.username", null);
            properties.comment("spring.datasource.password", null);
        }
        Path main = module.resolve("src/main/resources/application.properties");
        if (!Files.exists(main) && !properties.isEmpty()) {
            Files.createDirectories(main.getParent());
            Files.writeString(main, properties.render(), StandardCharsets.UTF_8);
            notes.add("src/main/resources/application.properties holds what the server's descriptors configured");
        }

        // The application itself.
        String app = Files.exists(baseDir.resolve("Application.java")) ? "SpringBootApp" : "Application";
        StringBuilder annotations = new StringBuilder("@SpringBootApplication\n");
        Set<String> appImports = new TreeSet<>(Set.of("org.springframework.boot.SpringApplication",
                "org.springframework.boot.autoconfigure.SpringBootApplication"));
        if (uses.servletComponents()) {
            annotations.append("@ServletComponentScan\n");
            appImports.add("org.springframework.boot.web.servlet.ServletComponentScan");
        }
        if (uses.async()) {
            annotations.append("@EnableAsync\n");
            appImports.add("org.springframework.scheduling.annotation.EnableAsync");
        }
        if (uses.scheduled()) {
            annotations.append("@EnableScheduling\n");
            appImports.add("org.springframework.scheduling.annotation.EnableScheduling");
        }
        if (webContent) {
            appImports.add("org.springframework.boot.builder.SpringApplicationBuilder");
            appImports.add("org.springframework.boot.web.servlet.support.SpringBootServletInitializer");
        }
        StringBuilder application = new StringBuilder(pkg);
        appImports.forEach(i -> application.append("import ").append(i).append(";\n"));
        application.append("\n/** Starts the application: ").append(webContent ? "as a WAR in a servlet container, or " : "")
                .append("on its own with an embedded server. */\n").append(annotations)
                .append("public class ").append(app).append(webContent ? " extends SpringBootServletInitializer" : "").append(" {\n\n")
                .append("    public static void main(String[] args) {\n        SpringApplication.run(").append(app).append(".class, args);\n    }\n");
        if (webContent) {
            application.append("\n    @Override\n    protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {\n")
                    .append("        return builder.sources(").append(app).append(".class);\n    }\n");
        }
        application.append("}\n");
        Files.writeString(baseDir.resolve(app + ".java"), application.toString(), StandardCharsets.UTF_8);
        notes.add(sources.relativize(baseDir.resolve(app + ".java")) + " starts the application");

        // A test that the application starts: the first thing to know about a re-platformed application.
        Path tests = module.resolve("src/test/java").resolve(base.replace('.', '/'));
        Path smoke = tests.resolve(app + "StartsTest.java");
        if (!Files.exists(smoke)) {
            Files.createDirectories(tests);
            // The settings are on the test, not in a test application.properties, which would hide the real one.
            String database = uses.jpa() ? "(properties = {\n        // An in-memory database, with the schema made from the entities.\n"
                    + "        \"spring.datasource.url=jdbc:h2:mem:starts;DB_CLOSE_DELAY=-1\",\n"
                    + "        \"spring.jpa.hibernate.ddl-auto=create-drop\"})" : "";
            Files.writeString(smoke, pkg + "import org.junit.jupiter.api.Test;\nimport org.springframework.boot.test.context.SpringBootTest;\n\n"
                    + "/** The application starts: every component is found and wired" + (uses.jpa() ? ", and the entities map to a schema" : "")
                    + ". */\n@SpringBootTest" + database + "\nclass " + app + "StartsTest {\n\n    @Test\n    void starts() {\n    }\n}\n",
                    StandardCharsets.UTF_8);
            notes.add("src/test/java/" + module.resolve("src/test/java").relativize(smoke) + " checks that the application starts");
        }

        // The build.
        boolean jsp = false;
        if (webContent) {
            try (Stream<Path> walk = Files.walk(module.resolve("src/main/webapp"))) {
                jsp = walk.anyMatch(p -> p.toString().endsWith(".jsp") || p.toString().endsWith(".jspx"));
            }
        }
        Files.writeString(buildFile, maven ? pom(pom, boot, uses, webContent, jsp, notes)
                : gradle(pom, boot, uses, webContent, jsp, buildFile.getFileName().toString().endsWith(".kts"), notes), StandardCharsets.UTF_8);
        return true;
    }

    // ---- sources -------------------------------------------------------------------------------------------

    /** Session beans, CDI scopes and container injection, as Spring's equivalents. */
    static String components(String source, boolean singlePersistenceUnit) {
        String s = source;
        String service = "org.springframework.stereotype.Service";
        String component = "org.springframework.stereotype.Component";
        String transactional = "org.springframework.transaction.annotation.Transactional";
        String scope = "org.springframework.context.annotation.Scope";
        // The container ran every business method of a session bean in a transaction.
        s = annotation(s, "jakarta.ejb.Stateless", "@Service\n@Transactional", service, transactional);
        s = annotation(s, "jakarta.ejb.Singleton", "@Service\n@Transactional", service, transactional);
        s = annotation(s, "jakarta.ejb.Stateful", "@Service\n@Scope(\"prototype\")\n@Transactional", service, scope, transactional);
        s = annotation(s, "jakarta.inject.Singleton", "@Component", component);
        s = annotation(s, "jakarta.enterprise.context.ApplicationScoped", "@Component", component);
        s = annotation(s, "jakarta.enterprise.context.Dependent", "@Component\n@Scope(\"prototype\")", component, scope);
        s = annotation(s, "jakarta.enterprise.context.RequestScoped", "@Component\n@RequestScope", component,
                "org.springframework.web.context.annotation.RequestScope");
        s = annotation(s, "jakarta.enterprise.context.SessionScoped", "@Component\n@SessionScope", component,
                "org.springframework.web.context.annotation.SessionScope");
        s = annotation(s, "jakarta.ejb.EJB", "@Autowired", "org.springframework.beans.factory.annotation.Autowired");
        s = annotation(s, "jakarta.ejb.Asynchronous", "@Async", "org.springframework.scheduling.annotation.Async");
        for (String gone : List.of("Startup", "LocalBean", "Local", "Remote", "Lock", "ConcurrencyManagement", "TransactionManagement")) {
            s = annotation(s, "jakarta.ejb." + gone, "");
        }
        if (uses(s, "jakarta.ejb.TransactionAttribute")) {
            Matcher m = Pattern.compile("@TransactionAttribute\\b(?:\\s*\\(\\s*(?:value\\s*=\\s*)?(?:TransactionAttributeType\\.)?(\\w+)\\s*\\))?").matcher(s);
            StringBuilder out = new StringBuilder();
            boolean propagation = false;
            while (m.find()) {
                String type = m.group(1);
                boolean required = type == null || type.equals("REQUIRED");
                propagation |= !required;
                m.appendReplacement(out, required ? "@Transactional" : "@Transactional(propagation = Propagation." + type + ")");
            }
            m.appendTail(out);
            s = withImports(withoutImport(withoutImport(out.toString(), "jakarta.ejb.TransactionAttribute"),
                    "jakarta.ejb.TransactionAttributeType"), propagation
                    ? new String[] {transactional, "org.springframework.transaction.annotation.Propagation"} : new String[] {transactional});
        }
        if (singlePersistenceUnit) {
            // Spring Boot has one persistence unit, and it is not called what persistence.xml called it.
            s = s.replaceAll("@PersistenceContext\\s*\\(\\s*unitName\\s*=\\s*\"[^\"]*\"\\s*\\)", "@PersistenceContext");
            s = s.replaceAll("(@PersistenceContext\\s*\\([^)]*?),\\s*unitName\\s*=\\s*\"[^\"]*\"", "$1");
            s = s.replaceAll("(@PersistenceContext\\s*\\()\\s*unitName\\s*=\\s*\"[^\"]*\"\\s*,\\s*", "$1");
        }
        return s;
    }

    /** Whether the file can mean {@code type} by its simple name: imported by name or by package. */
    private static boolean uses(String source, String type) {
        String pkg = type.substring(0, type.lastIndexOf('.'));
        return source.contains("import " + type + ";") || source.contains("import " + pkg + ".*;");
    }

    private static boolean imports(String source, String prefix) {
        return Pattern.compile("(?m)^import\\s+(static\\s+)?" + Pattern.quote(prefix) + "\\b").matcher(source).find();
    }

    /** Replaces {@code @Simple(...)} of the given type, where the file imports it, and settles the imports. */
    private static String annotation(String source, String type, String replacement, String... newImports) {
        if (!uses(source, type)) {
            return source;
        }
        String simple = type.substring(type.lastIndexOf('.') + 1);
        Pattern p = replacement.isEmpty()
                ? Pattern.compile("(?m)^[ \\t]*@" + simple + "\\b" + ARGS + "[ \\t]*\\r?\\n|@" + simple + "\\b" + ARGS + "[ \\t]*")
                : Pattern.compile("(?m)^([ \\t]*)@" + simple + "\\b" + ARGS + "|@" + simple + "\\b" + ARGS);
        Matcher m = p.matcher(source);
        StringBuilder out = new StringBuilder();
        boolean found = false;
        while (m.find()) {
            found = true;
            String indent = replacement.isEmpty() || m.group(1) == null ? "" : m.group(1);
            // Several annotations in place of one keep the line's indentation.
            m.appendReplacement(out, Matcher.quoteReplacement(indent + replacement.replace("\n", "\n" + indent)));
        }
        m.appendTail(out);
        return found ? withImports(withoutImport(out.toString(), type), newImports) : source;
    }

    private static String withoutImport(String source, String type) {
        return source.replaceAll("(?m)^import\\s+" + Pattern.quote(type) + "\\s*;[ \\t]*\\r?\\n", "");
    }

    static String withImports(String source, String... types) {
        String s = source;
        for (String type : types) {
            if (s.contains("import " + type + ";")) {
                continue;
            }
            Matcher last = Pattern.compile("(?m)^import\\s+[^;]+;[ \\t]*\\r?\\n").matcher(s);
            int at = -1;
            while (last.find()) {
                at = last.end();
            }
            if (at < 0) {
                Matcher pkg = PACKAGE.matcher(s);
                at = pkg.find() ? s.indexOf('\n', pkg.end()) + 1 : 0;
                s = s.substring(0, at) + "\nimport " + type + ";\n" + s.substring(at);
            } else {
                s = s.substring(0, at) + "import " + type + ";\n" + s.substring(at);
            }
        }
        return s;
    }

    private static String annotateType(String source, String annotation, String type) {
        Matcher declaration = TYPE.matcher(source);
        if (!declaration.find()) {
            return source;
        }
        // Above the type's first annotation, or the declaration itself.
        String head = source.substring(0, declaration.start());
        Matcher first = Pattern.compile("(?m)^@\\w").matcher(head);
        int lastBrace = Math.max(head.lastIndexOf(';'), head.lastIndexOf("*/"));
        int at = declaration.start();
        while (first.find()) {
            if (first.start() > lastBrace) {
                at = first.start();
                break;
            }
        }
        return withImports(source.substring(0, at) + annotation + "\n" + source.substring(at), type);
    }

    /** The annotations on the file's first type declaration. */
    private static String classAnnotations(String source) {
        Matcher declaration = TYPE.matcher(source);
        if (!declaration.find()) {
            return "";
        }
        String head = source.substring(0, declaration.start());
        int from = Math.max(head.lastIndexOf(';'), head.lastIndexOf("*/"));
        return head.substring(Math.max(from, 0));
    }

    private static boolean hasStereotype(String annotations) {
        return Pattern.compile("@(Component|Service|Repository|Controller|RestController|Configuration)\\b").matcher(annotations).find();
    }

    private static String typeName(String source) {
        Matcher m = TYPE.matcher(source);
        return m.find() ? m.group(2) : null;
    }

    private static String packageOf(String source) {
        Matcher m = PACKAGE.matcher(source);
        return m.find() ? m.group(1) : "";
    }

    private static String qualified(String source, String type) {
        String pkg = packageOf(source);
        return pkg.isEmpty() ? type : pkg + "." + type;
    }

    /** The package every source file is in or under: where the application class goes, so that it finds them all. */
    static String basePackage(Iterable<String> sources) {
        String[] common = null;
        for (String source : sources) {
            String[] parts = packageOf(source).split("\\.");
            if (common == null) {
                common = parts;
                continue;
            }
            int n = 0;
            while (n < common.length && n < parts.length && common[n].equals(parts[n])) {
                n++;
            }
            common = java.util.Arrays.copyOf(common, n);
        }
        return common == null ? "" : String.join(".", common);
    }

    // ---- descriptors ---------------------------------------------------------------------------------------

    /** Lines of an application.properties file, some of them commented out for a person to fill in. */
    static final class Properties {
        private final List<String> lines = new ArrayList<>();
        private final Set<String> keys = new LinkedHashSet<>();

        void put(String key, String value, String comment) {
            if (keys.add(key)) {
                if (comment != null) {
                    lines.add("# " + comment);
                }
                lines.add(key + "=" + value);
            }
        }

        void comment(String key, String comment) {
            if (keys.add(key)) {
                if (comment != null) {
                    lines.add("# " + comment);
                }
                lines.add("#" + key + "=");
            }
        }

        boolean isEmpty() {
            return lines.isEmpty();
        }

        String render() {
            return String.join("\n", lines) + "\n";
        }
    }

    private static int persistenceUnits(Path module) throws IOException {
        Path file = module.resolve("src/main/resources/META-INF/persistence.xml");
        if (!Files.isRegularFile(file)) {
            return 0;
        }
        return (int) Pattern.compile("<persistence-unit\\b").matcher(Files.readString(file, StandardCharsets.UTF_8)).results().count();
    }

    /**
     * Reads the descriptors only an application server uses, moves what they say into {@code properties} and
     * removes them.
     *
     * @return the context root the server was told to use, or null
     */
    private static String descriptors(Path module, Properties properties, List<String> notes) throws IOException {
        Path persistence = module.resolve("src/main/resources/META-INF/persistence.xml");
        if (Files.isRegularFile(persistence)) {
            String xml = Files.readString(persistence, StandardCharsets.UTF_8);
            if (Pattern.compile("<persistence-unit\\b").matcher(xml).results().count() == 1) {
                Matcher source = Pattern.compile("<(?:jta|non-jta)-data-source>\\s*([^<]+?)\\s*</").matcher(xml);
                String jndi = source.find() ? source.group(1) : null;
                Matcher property = Pattern.compile("<property\\s+name=\"([^\"]+)\"\\s+value=\"([^\"]*)\"").matcher(xml);
                while (property.find()) {
                    String name = property.group(1).replaceFirst("^javax\\.persistence\\.", "jakarta.persistence.");
                    String value = property.group(2);
                    switch (name) {
                        case "jakarta.persistence.schema-generation.database.action" -> properties.put("spring.jpa.hibernate.ddl-auto",
                                switch (value) {
                                    case "create" -> "create";
                                    case "drop-and-create" -> "create";
                                    case "drop" -> "create-drop";
                                    default -> "none";
                                }, null);
                        case "hibernate.hbm2ddl.auto" -> properties.put("spring.jpa.hibernate.ddl-auto", value, null);
                        case "hibernate.show_sql" -> properties.put("spring.jpa.show-sql", value, null);
                        // The database is told by the data source; the dialect follows from it.
                        case "hibernate.dialect", "hibernate.transaction.jta.platform" -> { }
                        default -> properties.put("spring.jpa.properties." + name, value, null);
                    }
                }
                Files.delete(persistence);
                notes.add("META-INF/persistence.xml → application.properties (Spring Boot finds the entities itself)"
                        + (jndi == null ? "" : "; the data source " + jndi + " was the server's: configure spring.datasource.*"));
            } else {
                notes.add("META-INF/persistence.xml declares several persistence units and is left as it is: each needs its own "
                        + "data source and entity manager factory configured by hand");
            }
        }
        String contextRoot = null;
        Path webInf = module.resolve("src/main/webapp/WEB-INF");
        for (String name : List.of("jboss-web.xml", "beans.xml", "ejb-jar.xml", "jboss-deployment-structure.xml", "glassfish-web.xml",
                "weblogic.xml", "ibm-web-bnd.xml", "ibm-web-ext.xml")) {
            for (Path file : List.of(webInf.resolve(name), module.resolve("src/main/resources/META-INF").resolve(name),
                    module.resolve("src/main/webapp/META-INF").resolve(name))) {
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                String xml = Files.readString(file, StandardCharsets.UTF_8);
                Matcher root = Pattern.compile("<context-root>\\s*([^<]+?)\\s*</context-root>").matcher(xml);
                if (root.find()) {
                    contextRoot = root.group(1).startsWith("/") ? root.group(1) : "/" + root.group(1);
                }
                // An empty beans.xml only switched CDI on; one that lists interceptors or alternatives says more.
                if (name.equals("ejb-jar.xml") || (name.equals("beans.xml")
                        && Pattern.compile("<(interceptors|decorators|alternatives)>\\s*<").matcher(xml).find())) {
                    notes.add(module.relativize(file) + " configures beans in XML and is left as it is: declare the same in Spring");
                    continue;
                }
                Files.delete(file);
                notes.add(module.relativize(file).toString().replace('\\', '/') + " removed: only an application server reads it");
            }
        }
        return contextRoot;
    }

    private static void deleteEmpty(Path folder) throws IOException {
        try (Stream<Path> walk = Files.walk(folder)) {
            for (Path dir : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(dir);
            }
        }
    }

    /** Whether the web folder holds pages or assets, and not just descriptors. */
    private static boolean hasWebContent(Path webapp) throws IOException {
        try (Stream<Path> walk = Files.walk(webapp)) {
            return walk.anyMatch(Files::isRegularFile);
        }
    }

    // ---- build ---------------------------------------------------------------------------------------------

    /** APIs the application server supplied; Spring Boot's starters bring their own. */
    private static boolean serverApi(String groupId, String artifactId) {
        return (groupId.equals("javax") || groupId.equals("jakarta.platform") || groupId.startsWith("org.jboss.spec")
                || groupId.startsWith("jakarta.") || groupId.startsWith("javax."))
                && (artifactId.contains("javaee") || artifactId.contains("jakartaee") || artifactId.endsWith("-api")
                || artifactId.contains("_spec"));
    }

    static String pom(String original, String boot, Uses uses, boolean webContent, boolean jsp, List<String> notes) {
        String pom = original;
        PomEditor.Result removed = PomEditor.removeDependencies(pom, SpringBootReplatformer::serverApi);
        pom = removed.content();
        pom = PomEditor.importBom(pom, "org.springframework.boot", "spring-boot-dependencies", boot).content();
        List<String> starters = new ArrayList<>();
        starters.add(uses.rest() ? "spring-boot-starter-jersey" : "spring-boot-starter-web");
        if (uses.jpa()) {
            starters.add("spring-boot-starter-data-jpa");
        }
        if (uses.validation()) {
            starters.add("spring-boot-starter-validation");
        }
        for (String starter : starters) {
            pom = PomEditor.addDependency(pom, "org.springframework.boot", starter, null, null).content();
        }
        if (uses.jaxb()) {
            // The JDK no longer has JAXB and no server supplies it; Spring Boot manages the version.
            pom = PomEditor.addDependency(pom, "jakarta.xml.bind", "jakarta.xml.bind-api", null, null).content();
            pom = PomEditor.addDependency(pom, "org.glassfish.jaxb", "jaxb-runtime", null, "runtime").content();
        }
        if (uses.ejb() && !uses.jpa()) {
            pom = PomEditor.addDependency(pom, "org.springframework", "spring-tx", null, null).content();
        }
        if (uses.inject() && !uses.rest()) {
            // Spring understands @Inject and @Named when their API is present.
            pom = PomEditor.addDependency(pom, "jakarta.inject", "jakarta.inject-api", null, null).content();
        }
        if (webContent) {
            pom = PomEditor.addDependency(pom, "org.springframework.boot", "spring-boot-starter-tomcat", null, "provided").content();
            if (jsp) {
                // The embedded server compiles JSP pages only with its JSP engine present.
                pom = PomEditor.addDependency(pom, "org.apache.tomcat.embed", "tomcat-embed-jasper", null, "provided").content();
            }
        } else {
            pom = PomEditor.setPackaging(pom, "jar").content();
        }
        // Versions the application set for what Spring Boot now manages would fight the versions it chose.
        pom = PomEditor.removeDependencyVersions(pom, (g, a) -> g.equals("org.glassfish.jaxb") || g.startsWith("org.hibernate")
                || g.startsWith("com.fasterxml.jackson") || (g.startsWith("jakarta.") && a.endsWith("-api"))).content();
        if (Pattern.compile("<groupId>\\s*junit\\s*</groupId>").matcher(pom).find()) {
            // The application's JUnit 4 tests keep running beside the JUnit 5 test added here.
            pom = PomEditor.addDependency(pom, "org.junit.vintage", "junit-vintage-engine", null, "test").content();
        }
        pom = PomEditor.addDependency(pom, "org.springframework.boot", "spring-boot-starter-test", null, "test").content();
        if (uses.jpa()) {
            pom = PomEditor.addDependency(pom, "com.h2database", "h2", null, "test").content();
        }
        // Spring reads parameter names; the parent pom would have set this.
        pom = PomEditor.setProperty(pom, "maven.compiler.parameters", "true").content();
        String unit = "    ";
        pom = PomEditor.addPluginXml(pom, "spring-boot-maven-plugin", unit.repeat(3) + "<plugin>\n" + unit.repeat(4)
                + "<groupId>org.springframework.boot</groupId>\n" + unit.repeat(4) + "<artifactId>spring-boot-maven-plugin</artifactId>\n"
                + unit.repeat(4) + "<version>" + boot + "</version>\n" + unit.repeat(4) + "<executions>\n" + unit.repeat(5)
                + "<execution>\n" + unit.repeat(6) + "<goals>\n" + unit.repeat(7) + "<goal>repackage</goal>\n" + unit.repeat(6)
                + "</goals>\n" + unit.repeat(5) + "</execution>\n" + unit.repeat(4) + "</executions>\n" + unit.repeat(3) + "</plugin>\n")
                .content();
        // The generated test is JUnit 5, which an old Surefire does not run.
        pom = PomEditor.setPluginVersion(pom, "org.apache.maven.plugins", "maven-surefire-plugin", "3.5.2").content();
        notes.add("pom.xml: Spring Boot " + boot + " manages the versions; " + String.join(", ", starters)
                + (removed.changes() > 0 ? " in place of " + removed.changes() + " server API dependenc" + (removed.changes() == 1 ? "y" : "ies") : "")
                + "; packaged as " + (webContent ? "a WAR that also runs on its own" : "an executable jar"));
        return pom;
    }

    // ---- Gradle ----------------------------------------------------------------------------------------------

    static final String DEPENDENCY_MANAGEMENT = "1.1.7";
    private static final Pattern GRADLE_WAR = Pattern.compile("(?m)^\\s*(id\\s*\\(?\\s*['\"]war['\"]\\s*\\)?|apply\\s+plugin:\\s*['\"]war['\"]|`?war`?)\\s*$");
    private static final Pattern GRADLE_DEPENDENCY = Pattern.compile(
            "(?m)^[ \\t]*\\w+[ \\t]*\\(?[ \\t]*['\"]([^:'\"\\s]+):([^:'\"\\s]+)(?::[^'\"]*)?['\"][ \\t]*\\)?[ \\t]*\\R");
    private static final Pattern GRADLE_MANAGED = Pattern.compile(
            "(['\"])((?:org\\.glassfish\\.jaxb|org\\.hibernate[\\w.]*|com\\.fasterxml\\.jackson[\\w.]*):[^:'\"\\s]+|jakarta\\.[\\w.]+:[^:'\"\\s]+-api):[^'\"]+\\1");

    /** The name Gradle gives the WAR, which is the address a server published it under: the archive's or the project's. */
    private static String gradleArchiveName(Path module, String build) throws IOException {
        Matcher named = Pattern.compile("archive(?:Base|File)Name(?:\\.set\\(|\\s*=\\s*)['\"]([^'\"]+?)(?:\\.war)?['\"]").matcher(build);
        if (named.find()) {
            return named.group(1);
        }
        for (String settings : new String[] {"settings.gradle", "settings.gradle.kts"}) {
            if (Files.isRegularFile(module.resolve(settings))) {
                Matcher root = Pattern.compile("rootProject\\.name\\s*=\\s*['\"]([^'\"]+)['\"]").matcher(Files.readString(module.resolve(settings)));
                if (root.find()) {
                    return root.group(1);
                }
            }
        }
        return module.toAbsolutePath().normalize().getFileName().toString();
    }

    /**
     * The Gradle build of the re-platformed module: Spring Boot's plugin and its dependency management in
     * place of the versions the build wrote, starters in place of the platform API, and JUnit 5 for the
     * generated test beside whatever the project's own tests use.
     */
    static String gradle(String original, String boot, Uses uses, boolean webContent, boolean jsp, boolean kotlin, List<String> notes) {
        String build = original;
        // The platform API the server supplied.
        int removed = 0;
        Matcher declared = GRADLE_DEPENDENCY.matcher(build);
        StringBuilder kept = new StringBuilder();
        while (declared.find()) {
            boolean server = serverApi(declared.group(1), declared.group(2));
            removed += server ? 1 : 0;
            declared.appendReplacement(kept, server ? "" : Matcher.quoteReplacement(declared.group()));
        }
        build = declared.appendTail(kept).toString();
        // Versions the application set for what Spring Boot now manages would fight the versions it chose.
        build = GRADLE_MANAGED.matcher(build).replaceAll("$1$2$1");

        String q = kotlin ? "\"" : "'";
        List<String> plugins = new ArrayList<>();
        if (!Pattern.compile("(?m)^\\s*(id\\s*\\(?\\s*['\"](java|java-library|war)['\"]|apply\\s+plugin:\\s*['\"](java|java-library|war)['\"]|`?(java|war|`java-library`)`?\\s*$)")
                .matcher(build).find()) {
            plugins.add(kotlin ? "java" : "id 'java'");
        }
        plugins.add(kotlin ? "id(\"org.springframework.boot\") version \"" + boot + "\"" : "id 'org.springframework.boot' version '" + boot + "'");
        plugins.add(kotlin ? "id(\"io.spring.dependency-management\") version \"" + DEPENDENCY_MANAGEMENT + "\""
                : "id 'io.spring.dependency-management' version '" + DEPENDENCY_MANAGEMENT + "'");
        build = addGradlePlugins(build, plugins);
        if (!webContent) {
            // Nothing is left to deploy to a server: an executable jar.
            build = build.replaceAll("(?m)^[ \\t]*(id\\s*\\(?\\s*['\"]war['\"]\\s*\\)?|apply\\s+plugin:\\s*['\"]war['\"]|`?war`?)[ \\t]*\\R", "");
            if (!Pattern.compile("(?m)^\\s*(id\\s*\\(?\\s*['\"]java(-library)?['\"]|apply\\s+plugin:\\s*['\"]java(-library)?['\"]|`?java`?\\s*$|`java-library`)").matcher(build).find()) {
                build = addGradlePlugins(build, List.of(kotlin ? "java" : "id 'java'"));
            }
            // Configurations only the war plugin has.
            build = build.replaceAll("(?m)^([ \\t]*)providedCompile\\b", "$1compileOnly").replaceAll("(?m)^([ \\t]*)providedRuntime\\b", "$1runtimeOnly");
        }

        List<String> starters = new ArrayList<>();
        starters.add(uses.rest() ? "spring-boot-starter-jersey" : "spring-boot-starter-web");
        if (uses.jpa()) {
            starters.add("spring-boot-starter-data-jpa");
        }
        if (uses.validation()) {
            starters.add("spring-boot-starter-validation");
        }
        for (String starter : starters) {
            build = GradleBuildFixer.addDependency(build, "implementation", "org.springframework.boot:" + starter, kotlin);
        }
        if (uses.jaxb()) {
            build = GradleBuildFixer.addDependency(build, "implementation", "jakarta.xml.bind:jakarta.xml.bind-api", kotlin);
            build = GradleBuildFixer.addDependency(build, "runtimeOnly", "org.glassfish.jaxb:jaxb-runtime", kotlin);
        }
        if (uses.ejb() && !uses.jpa()) {
            build = GradleBuildFixer.addDependency(build, "implementation", "org.springframework:spring-tx", kotlin);
        }
        if (uses.inject() && !uses.rest()) {
            build = GradleBuildFixer.addDependency(build, "implementation", "jakarta.inject:jakarta.inject-api", kotlin);
        }
        if (webContent) {
            build = GradleBuildFixer.addDependency(build, "providedRuntime", "org.springframework.boot:spring-boot-starter-tomcat", kotlin);
            if (jsp) {
                build = GradleBuildFixer.addDependency(build, "providedRuntime", "org.apache.tomcat.embed:tomcat-embed-jasper", kotlin);
            }
        }
        if (Pattern.compile("['\"]junit:junit[:'\"]").matcher(build).find()) {
            // The application's JUnit 4 tests keep running beside the JUnit 5 test added here.
            build = GradleBuildFixer.addDependency(build, "testRuntimeOnly", "org.junit.vintage:junit-vintage-engine", kotlin);
        }
        build = GradleBuildFixer.addDependency(build, "testImplementation", "org.springframework.boot:spring-boot-starter-test", kotlin);
        build = GradleBuildFixer.addDependency(build, "testRuntimeOnly", "org.junit.platform:junit-platform-launcher", kotlin);
        if (uses.jpa()) {
            build = GradleBuildFixer.addDependency(build, "testRuntimeOnly", "com.h2database:h2", kotlin);
        }
        if (!build.contains("mavenCentral()")) {
            build += (build.endsWith("\n") ? "" : "\n") + "\nrepositories {\n    mavenCentral()\n}\n";
        }
        if (!build.contains("useJUnitPlatform")) {
            // The generated test is JUnit 5, which Gradle runs only when told to.
            build += (build.endsWith("\n") ? "" : "\n") + "\n" + (kotlin ? "tasks.withType<Test> {\n    useJUnitPlatform()\n}\n"
                    : "tasks.withType(Test).configureEach {\n    useJUnitPlatform()\n}\n");
        }
        // Lines taken out leave their blank lines behind.
        build = build.replaceAll("\\n{3,}", "\n\n");
        notes.add("the Gradle build: Spring Boot " + boot + " manages the versions; " + String.join(", ", starters)
                + (removed > 0 ? " in place of " + removed + " server API dependenc" + (removed == 1 ? "y" : "ies") : "")
                + "; packaged as " + (webContent ? "a WAR that also runs on its own (bootWar)" : "an executable jar (bootJar)"));
        return build;
    }

    /** Adds plugin lines to the build's {@code plugins} block, which it gets, where Gradle wants it, if it has none. */
    static String addGradlePlugins(String build, List<String> lines) {
        Matcher block = Pattern.compile("(?m)^plugins\\s*\\{").matcher(build);
        if (block.find()) {
            int close = build.indexOf("\n}", block.end());
            if (close >= 0) {
                String body = build.substring(block.end(), close);
                Matcher first = Pattern.compile("(?m)^([ \\t]+)\\S").matcher(body);
                String indent = first.find() ? first.group(1) : "    ";
                StringBuilder added = new StringBuilder();
                lines.forEach(l -> added.append("\n").append(indent).append(l));
                return build.substring(0, close) + added + build.substring(close);
            }
        }
        // Before everything but a buildscript block, as Gradle requires.
        StringBuilder plugins = new StringBuilder("plugins {\n");
        lines.forEach(l -> plugins.append("    ").append(l).append("\n"));
        plugins.append("}\n\n");
        Matcher buildscript = Pattern.compile("(?m)^buildscript\\s*\\{").matcher(build);
        if (buildscript.find()) {
            int close = build.indexOf("\n}", buildscript.end());
            if (close >= 0) {
                int after = Math.min(build.length(), close + 3);
                return build.substring(0, after) + "\n" + plugins + build.substring(after).replaceFirst("^\\R+", "");
            }
        }
        return plugins + build.replaceFirst("^\\R+", "");
    }

    private static String xml(String text, String tag) {
        Matcher m = Pattern.compile("<" + tag + ">\\s*([^<]*?)\\s*</" + tag + ">").matcher(text);
        return m.find() ? m.group(1) : null;
    }
}
