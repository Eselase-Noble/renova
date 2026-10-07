package io.renova.php;

import io.renova.core.util.Versions;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Composer version constraints, as far as a migration needs to read them. */
public final class Constraints {

    private static final Pattern VERSION = Pattern.compile("(\\d+(?:\\.\\d+){0,3})");

    private Constraints() {
    }

    /**
     * The lowest version a constraint allows: "7.4" for {@code ^7.4|^8.0}, "7.2.5" for {@code >=7.2.5}, "5.6" for
     * {@code ~5.6.0 || ^7.0}. Null for a constraint that names none ({@code *}, a branch).
     */
    public static String lowest(String constraint) {
        if (constraint == null) {
            return null;
        }
        String lowest = null;
        for (String alternative : constraint.split("\\|\\|?")) {
            // Of "a b" or "a, b" (both must hold) the first comparison is the lower bound.
            Matcher m = VERSION.matcher(alternative.replaceAll("<=?\\s*[\\d.]+", ""));
            if (m.find()) {
                String version = m.group(1).replaceAll("(\\.0)+$", "");
                version = version.contains(".") ? version : version + ".0";
                if (lowest == null || Versions.isBelow(version, lowest)) {
                    lowest = version;
                }
            }
        }
        return lowest;
    }

    /** "8.83.27" for "v8.83.27", as Composer's lock file writes versions. */
    public static String plain(String version) {
        return version == null ? null : version.replaceFirst("^[vV]", "");
    }
}
