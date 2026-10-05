package io.renova.web.store;

/**
 * A legacy project registered with the server.
 *
 * @param path      directory on the server that holds the project; never modified
 * @param playbook  the playbook used by default (detected when the project is added)
 * @param createdAt ISO-8601 instant
 */
public record Project(String id, String name, String path, String ecosystem, String playbook, String createdAt) {
}
