package io.renova.web.audit;

/**
 * Something a member did that changed the organisation, or that others should be able to trace.
 *
 * @param at        ISO-8601 instant
 * @param actorId   id of the user; null for the server itself
 * @param actorName the user's name when it happened, so the entry still reads after an account is removed
 * @param action    what happened, as {@code area.verb}, for example {@code migration.started}
 * @param target    what it happened to (a project name, an email, a provider); may be null
 * @param detail    one line of context; never a secret
 */
public record AuditEvent(String id, String at, String organisationId, String actorId, String actorName, String action,
                         String target, String detail) {
}
