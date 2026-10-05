package io.renova.core.engine;

/** @param file workspace-relative path, or null for errors not tied to a file */
public record BuildError(String file, int line, String message) {
}
