package io.renova.web.store;

/**
 * A legacy project registered with the server.
 *
 * @param path      directory on the server that holds the project; never modified
 * @param playbook  the playbook used by default (detected when the project is added)
 * @param createdAt      ISO-8601 instant
 * @param organisationId the organisation it belongs to
 */
public record Project(String id, String name, String path, String ecosystem, String playbook, String createdAt,
                      String organisationId) {

    public Project withPlaybook(String playbookId) {
        return new Project(id, name, path, ecosystem, playbookId, createdAt, organisationId);
    }

    public Project withOrganisation(String id) {
        return new Project(this.id, name, path, ecosystem, playbook, createdAt, id);
    }
}
