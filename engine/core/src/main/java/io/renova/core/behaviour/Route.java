package io.renova.core.behaviour;

import java.util.Locale;

/**
 * An entry point of the application and the file that handles it, used to send a behaviour difference
 * to the code that produces it.
 *
 * @param method      HTTP method, or null for any
 * @param template    path with {@code {variables}}, e.g. "/claims/{id}"
 * @param handlerFile project-relative file that handles it, e.g. a controller or a JSP
 */
public record Route(String method, String template, String handlerFile) {

    /**
     * How well a request matches this route: -1 when it does not, else the number of literal segments
     * that matched (more is more specific). Captured values ({@code ${name}}) match any segment.
     */
    public int match(String requestMethod, String path) {
        if (method != null && !method.equalsIgnoreCase(requestMethod)) {
            return -1;
        }
        String[] wanted = segments(template);
        String[] actual = segments(path.replaceFirst("[?#].*", ""));
        if (wanted.length != actual.length) {
            return -1;
        }
        int literal = 0;
        for (int i = 0; i < wanted.length; i++) {
            boolean variable = wanted[i].startsWith("{") || actual[i].startsWith("${");
            if (!variable && !wanted[i].equals(actual[i])) {
                return -1;
            }
            literal += variable ? 0 : 1;
        }
        return literal;
    }

    private static String[] segments(String path) {
        String p = path.toLowerCase(Locale.ROOT).replaceAll("/+$", "");
        return p.isEmpty() ? new String[0] : p.substring(p.startsWith("/") ? 1 : 0).split("/");
    }
}
