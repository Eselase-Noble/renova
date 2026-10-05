package io.renova.core.ai;

/**
 * One file sent to the AI provider.
 *
 * @param why for related and reference files: why the file is included
 */
public record RequestFile(String path, String content, Role role, String why) {

    public enum Role {
        /** A file the request is about: it has matched rules or build errors. Editable. */
        TARGET,
        /** A file the fix may also need to change, e.g. the owning build file. Editable. */
        RELATED,
        /** Context only; edits to it are rejected. */
        REFERENCE
    }

    public boolean editable() {
        return role != Role.REFERENCE;
    }
}
