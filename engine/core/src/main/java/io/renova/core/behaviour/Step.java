package io.renova.core.behaviour;

import java.util.List;
import java.util.Map;

/**
 * One request of a scenario. {@code ${name}} in the path, header values and (when
 * {@code substituteBody}) the body is replaced with a value captured by an earlier step of the same run.
 *
 * @param body     request body bytes, already encoded (form, JSON, multipart); empty for none
 * @param captures name to capture spec: {@code body:REGEX} or {@code header:NAME:REGEX}; group 1, or the whole match
 * @param ignore   regexes whose matches are left out when comparing this step's response bodies
 */
public record Step(String method, String path, Map<String, String> headers, byte[] body, boolean substituteBody,
                   Map<String, String> captures, List<String> ignore) {

    public Step {
        method = method == null ? "GET" : method.toUpperCase(java.util.Locale.ROOT);
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException("Step path must start with '/': " + path);
        }
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        body = body == null ? new byte[0] : body;
        captures = captures == null ? Map.of() : Map.copyOf(captures);
        ignore = ignore == null ? List.of() : List.copyOf(ignore);
    }

    public static Step get(String path) {
        return new Step("GET", path, null, null, false, null, null);
    }

    /** Whether the request may change the application's state. */
    public boolean mutating() {
        return !(method.equals("GET") || method.equals("HEAD") || method.equals("OPTIONS"));
    }
}
