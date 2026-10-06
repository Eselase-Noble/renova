package io.renova.java.fix;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a web.xml declares, as Spring Boot declares it: servlets, filters and listeners as registration beans
 * in a configuration class, context parameters and the session timeout as properties. Spring Boot's embedded
 * server never reads web.xml, so anything left only there would silently stop existing.
 */
final class WebXml {

    private WebXml() {
    }

    /**
     * @param basePackage the package of the class to generate
     * @param properties  receives the settings that are properties in Spring Boot
     * @param notes       receives what could not be carried over
     * @return the source of {@code WebConfiguration}, or null when the descriptor declares no component
     */
    static String configuration(String xml, String basePackage, SpringBootReplatformer.Properties properties, List<String> notes) {
        Element root;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            root = document.getDocumentElement();
        } catch (Exception e) {
            notes.add("WEB-INF/web.xml could not be read (" + e.getMessage() + "): declare its servlets and filters as Spring beans");
            return null;
        }
        for (Element parameter : children(root, "context-param")) {
            properties.put("server.servlet.context-parameters." + text(parameter, "param-name"), text(parameter, "param-value"), null);
        }
        for (Element session : children(root, "session-config")) {
            String timeout = text(session, "session-timeout");
            if (timeout != null && timeout.matches("\\d+")) {
                properties.put("server.servlet.session.timeout", timeout + "m", null);
            }
        }
        Map<String, List<String>> servletPatterns = new LinkedHashMap<>();
        for (Element mapping : children(root, "servlet-mapping")) {
            servletPatterns.computeIfAbsent(text(mapping, "servlet-name"), k -> new ArrayList<>()).addAll(texts(mapping, "url-pattern"));
        }
        StringBuilder beans = new StringBuilder();
        int count = 0;
        for (Element servlet : children(root, "servlet")) {
            String name = text(servlet, "servlet-name");
            String type = text(servlet, "servlet-class");
            if (type == null) {
                notes.add("web.xml servlet '" + name + "' is a JSP file mapping: reach the page by its own path, or forward to it from a controller");
                continue;
            }
            List<String> patterns = servletPatterns.getOrDefault(name, List.of());
            beans.append("\n    @Bean\n    public ServletRegistrationBean<").append(type).append("> ").append(identifier(name, "Servlet"))
                    .append("() {\n        ServletRegistrationBean<").append(type).append("> bean = new ServletRegistrationBean<>(new ")
                    .append(type).append("()").append(patterns.isEmpty() ? "" : ", " + quoted(patterns)).append(");\n")
                    .append("        bean.setName(").append(quote(name)).append(");\n");
            String startup = text(servlet, "load-on-startup");
            if (startup != null && startup.matches("-?\\d+")) {
                beans.append("        bean.setLoadOnStartup(").append(startup).append(");\n");
            }
            initParameters(servlet, beans);
            beans.append("        return bean;\n    }\n");
            count++;
        }
        Map<String, List<String>> filterPatterns = new LinkedHashMap<>();
        Map<String, List<String>> filterServlets = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        for (Element mapping : children(root, "filter-mapping")) {
            String name = text(mapping, "filter-name");
            if (!order.contains(name)) {
                order.add(name);
            }
            filterPatterns.computeIfAbsent(name, k -> new ArrayList<>()).addAll(texts(mapping, "url-pattern"));
            filterServlets.computeIfAbsent(name, k -> new ArrayList<>()).addAll(texts(mapping, "servlet-name"));
            if (!texts(mapping, "dispatcher").isEmpty()) {
                notes.add("web.xml filter '" + name + "' names dispatcher types (" + String.join(", ", texts(mapping, "dispatcher"))
                        + "): set them on its FilterRegistrationBean in WebConfiguration");
            }
        }
        for (Element filter : children(root, "filter")) {
            String name = text(filter, "filter-name");
            String type = text(filter, "filter-class");
            beans.append("\n    @Bean\n    public FilterRegistrationBean<").append(type).append("> ").append(identifier(name, "Filter"))
                    .append("() {\n        FilterRegistrationBean<").append(type).append("> bean = new FilterRegistrationBean<>(new ")
                    .append(type).append("());\n        bean.setName(").append(quote(name)).append(");\n");
            List<String> patterns = filterPatterns.getOrDefault(name, List.of());
            if (!patterns.isEmpty()) {
                beans.append("        bean.addUrlPatterns(").append(quoted(patterns)).append(");\n");
            }
            List<String> servlets = filterServlets.getOrDefault(name, List.of());
            if (!servlets.isEmpty()) {
                beans.append("        bean.addServletNames(").append(quoted(servlets)).append(");\n");
            }
            // Filters ran in the order of their mappings.
            beans.append("        bean.setOrder(").append(order.contains(name) ? order.indexOf(name) + 1 : order.size() + 1).append(");\n");
            initParameters(filter, beans);
            beans.append("        return bean;\n    }\n");
            count++;
        }
        for (Element listener : children(root, "listener")) {
            String type = text(listener, "listener-class");
            String simple = type.substring(type.lastIndexOf('.') + 1);
            beans.append("\n    @Bean\n    public ServletListenerRegistrationBean<").append(type).append("> ").append(identifier(simple, "Listener"))
                    .append("() {\n        return new ServletListenerRegistrationBean<>(new ").append(type).append("());\n    }\n");
            count++;
        }
        for (String other : List.of("security-constraint", "login-config", "error-page", "resource-ref", "ejb-ref", "ejb-local-ref",
                "env-entry", "jsp-config")) {
            int n = children(root, other).size();
            if (n > 0) {
                notes.add("web.xml had " + n + " <" + other + "> element(s), which Spring Boot configures differently: " + switch (other) {
                    case "security-constraint", "login-config" -> "protect the same addresses with Spring Security";
                    case "error-page" -> "use an ErrorPageRegistrar bean or a page under /error";
                    case "jsp-config" -> "tag libraries are found in the jars; page encodings go to server.servlet.jsp.*";
                    default -> "inject the resource, or set the value in application.properties";
                });
            }
        }
        if (count == 0) {
            return null;
        }
        return (basePackage.isEmpty() ? "" : "package " + basePackage + ";\n\n")
                + (beans.indexOf("FilterRegistrationBean") >= 0 ? "import org.springframework.boot.web.servlet.FilterRegistrationBean;\n" : "")
                + (beans.indexOf("ServletListenerRegistrationBean") >= 0
                ? "import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;\n" : "")
                + (beans.indexOf("ServletRegistrationBean<") >= 0 ? "import org.springframework.boot.web.servlet.ServletRegistrationBean;\n" : "")
                + "import org.springframework.context.annotation.Bean;\nimport org.springframework.context.annotation.Configuration;\n\n"
                + "/** The servlets, filters and listeners that web.xml declared, with the same names, addresses and order. */\n"
                + "@Configuration\npublic class WebConfiguration {\n" + beans + "}\n";
    }

    private static void initParameters(Element component, StringBuilder beans) {
        for (Element parameter : children(component, "init-param")) {
            beans.append("        bean.addInitParameter(").append(quote(text(parameter, "param-name"))).append(", ")
                    .append(quote(text(parameter, "param-value"))).append(");\n");
        }
    }

    /** "payslip-export" → "payslipExportServlet": a bean method name that cannot clash with a class or a keyword. */
    private static String identifier(String name, String suffix) {
        StringBuilder id = new StringBuilder();
        boolean upper = false;
        for (char c : (name == null ? "" : name).toCharArray()) {
            if (!Character.isJavaIdentifierPart(c)) {
                upper = true;
            } else {
                id.append(id.isEmpty() ? Character.toLowerCase(c) : upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        if (id.isEmpty() || !Character.isJavaIdentifierStart(id.charAt(0))) {
            id.insert(0, "web");
        }
        return id.toString().endsWith(suffix) ? id.toString() : id + suffix;
    }

    private static String quoted(List<String> values) {
        return String.join(", ", values.stream().map(WebXml::quote).toList());
    }

    private static String quote(String value) {
        return "\"" + (value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"")) + "\"";
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element && name.equals(local(element))) {
                result.add(element);
            }
        }
        return result;
    }

    private static String local(Element element) {
        String name = element.getTagName();
        return name.contains(":") ? name.substring(name.indexOf(':') + 1) : name;
    }

    private static String text(Element parent, String name) {
        List<Element> found = children(parent, name);
        return found.isEmpty() ? null : found.getFirst().getTextContent().strip();
    }

    private static List<String> texts(Element parent, String name) {
        return children(parent, name).stream().map(e -> e.getTextContent().strip()).toList();
    }
}
