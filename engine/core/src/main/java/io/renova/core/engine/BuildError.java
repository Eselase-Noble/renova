package io.renova.core.engine;

/** @param file workspace-relative path, or null for errors not tied to a file */
public record BuildError(String file, int line, String message) {

    /** True for a test that ran and failed, as the verifiers word it; false for what a compiler or the build reported. */
    public boolean fromFailedTest() {
        return message != null && message.startsWith("test ") && message.contains(" failed: ");
    }
}
