package io.renova.core.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Per-user settings file, by default {@code ~/.config/renova/config.properties}. It may hold API
 * keys, so it is created readable by its owner only where the file system supports that.
 */
public final class UserConfig {

    private final Path file;

    public UserConfig(Path file) {
        this.file = file;
    }

    /** {@code $RENOVA_CONFIG_HOME}, else {@code $XDG_CONFIG_HOME/renova}, else {@code ~/.config/renova}. */
    public static UserConfig defaultLocation() {
        String home = System.getenv("RENOVA_CONFIG_HOME");
        Path dir;
        if (home != null && !home.isBlank()) {
            dir = Path.of(home);
        } else if (System.getenv("XDG_CONFIG_HOME") != null && !System.getenv("XDG_CONFIG_HOME").isBlank()) {
            dir = Path.of(System.getenv("XDG_CONFIG_HOME"), "renova");
        } else if (System.getenv("APPDATA") != null) {
            dir = Path.of(System.getenv("APPDATA"), "renova");
        } else {
            dir = Path.of(System.getProperty("user.home"), ".config", "renova");
        }
        return new UserConfig(dir.resolve("config.properties"));
    }

    public Path file() {
        return file;
    }

    public Map<String, String> read() throws IOException {
        Map<String, String> values = new TreeMap<>();
        if (!Files.isRegularFile(file)) {
            return values;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        }
        props.stringPropertyNames().forEach(k -> values.put(k, props.getProperty(k)));
        return values;
    }

    public void set(String key, String value) throws IOException {
        Map<String, String> values = read();
        values.put(key, value);
        write(values);
    }

    /** Returns false when the key was not set. */
    public boolean unset(String key) throws IOException {
        Map<String, String> values = read();
        boolean removed = values.remove(key) != null;
        if (removed) {
            write(values);
        }
        return removed;
    }

    private void write(Map<String, String> values) throws IOException {
        Files.createDirectories(file.getParent());
        if (!Files.exists(file)) {
            Files.createFile(file);
            restrictToOwner(file);
        }
        Properties props = new Properties();
        props.putAll(values);
        try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.TRUNCATE_EXISTING)) {
            props.store(out, "Renova user configuration. May contain API keys: keep it private.");
        }
    }

    private static void restrictToOwner(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // Non-POSIX file system (Windows): the user profile directory is already private.
        }
    }
}
