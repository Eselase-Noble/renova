package io.renova.dotnet;

import io.renova.core.util.Versions;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Target framework monikers: what a project file says it is built for. {@code net48} and {@code v4.8} are
 * .NET Framework, {@code netcoreapp3.1} is .NET Core, {@code net6.0} and later (with a dot) are modern .NET,
 * and {@code netstandard2.0} is a contract that libraries for both worlds are built against.
 */
public final class Tfm {

    public enum Kind { FRAMEWORK, CORE, MODERN, STANDARD, OTHER }

    private static final Pattern FRAMEWORK = Pattern.compile("^net(\\d)(\\d)(\\d)?$");
    private static final Pattern DOTTED = Pattern.compile("^(netcoreapp|netstandard|net)(\\d+\\.\\d+)(-[\\w.]+)?$");

    private Tfm() {
    }

    /** {@code v4.7.2}, as old project files write it, to {@code net472}. */
    public static String fromFrameworkVersion(String version) {
        return "net" + version.strip().replaceFirst("^[vV]", "").replace(".", "");
    }

    public static Kind kind(String tfm) {
        String t = tfm.strip().toLowerCase(java.util.Locale.ROOT);
        if (FRAMEWORK.matcher(t).matches()) {
            return Kind.FRAMEWORK;
        }
        Matcher m = DOTTED.matcher(t);
        if (!m.matches()) {
            return Kind.OTHER;
        }
        return switch (m.group(1)) {
            case "netcoreapp" -> Kind.CORE;
            case "netstandard" -> Kind.STANDARD;
            default -> Kind.MODERN;
        };
    }

    /** The version in a moniker: "4.8" for net48, "3.1" for netcoreapp3.1, "6.0" for net6.0-windows. */
    public static String version(String tfm) {
        String t = tfm.strip().toLowerCase(java.util.Locale.ROOT);
        Matcher f = FRAMEWORK.matcher(t);
        if (f.matches()) {
            return f.group(1) + "." + f.group(2) + (f.group(3) == null ? "" : "." + f.group(3));
        }
        Matcher m = DOTTED.matcher(t);
        return m.matches() ? m.group(2) : "0";
    }

    /** The platform suffix with its dash ("-windows", "-windows10.0.19041.0"), or "". */
    public static String platform(String tfm) {
        Matcher m = DOTTED.matcher(tfm.strip().toLowerCase(java.util.Locale.ROOT));
        return m.matches() && m.group(3) != null ? m.group(3) : "";
    }

    /**
     * Whether a project built for {@code tfm} has to move to reach modern .NET {@code target} ("10.0"):
     * .NET Framework and .NET Core always, modern .NET when it is older. A .NET Standard library already runs
     * there and is left alone.
     */
    public static boolean below(String tfm, String target) {
        return switch (kind(tfm)) {
            case FRAMEWORK, CORE -> true;
            case MODERN -> Versions.isBelow(version(tfm), target);
            case STANDARD, OTHER -> false;
        };
    }

    /** The moniker for modern .NET {@code target}, keeping the platform a project was already tied to. */
    public static String modern(String target, String platform) {
        return "net" + target + (platform == null ? "" : platform);
    }
}
