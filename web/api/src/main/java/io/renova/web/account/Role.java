package io.renova.web.account;

/** What a member of an organisation may do. Each role includes everything the roles before it allow. */
public enum Role {
    /** Sees projects, assessments, migrations and reports. */
    VIEWER,
    /** Also starts migrations. */
    MEMBER,
    /** Also adds and removes projects, sets the AI provider and keys, and manages members and invitations. */
    ADMIN,
    /** Also manages owners. An organisation always keeps at least one owner. */
    OWNER;

    public boolean atLeast(Role other) {
        return compareTo(other) >= 0;
    }
}
