package io.renova.web.account;

/**
 * An invitation to join an organisation. Only a hash of the token is stored; the token itself is shown
 * once, to the admin who creates the invitation, as part of a link.
 */
public record Invitation(String id, String organisationId, String email, Role role, String tokenHash, String invitedBy,
                         String createdAt, String expiresAt) {
}
