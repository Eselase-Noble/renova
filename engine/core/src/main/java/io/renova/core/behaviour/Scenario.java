package io.renova.core.behaviour;

/**
 * One request sent to both the original and the migrated application.
 *
 * @param path request path and query, relative to the application root, e.g. "/orders/1"
 * @param why  where the scenario comes from, e.g. "OrderController#show (Spring @RequestMapping)"
 */
public record Scenario(String id, String method, String path, String why) {

    public Scenario {
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException("Scenario path must start with '/': " + path);
        }
        method = method == null ? "GET" : method;
    }
}
