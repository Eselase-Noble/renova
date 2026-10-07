package io.renova.dotnet.fix;

import io.renova.core.engine.MigrationContext;
import io.renova.core.util.Proc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds the .NET SDK on this machine and says how to run it so that nothing is left behind or sent out. */
public final class Dotnet {

    private Dotnet() {
    }

    /**
     * The {@code dotnet} executable: {@code RENOVA_DOTNET}, then {@code DOTNET_ROOT}, the PATH, and the places
     * the installers put it ({@code ~/.dotnet}, /usr/share/dotnet, /usr/lib/dotnet, /usr/local/share/dotnet,
     * Program Files).
     */
    public static Optional<Path> executable() {
        String name = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win") ? "dotnet.exe" : "dotnet";
        List<Path> candidates = new ArrayList<>();
        if (System.getenv("RENOVA_DOTNET") != null) {
            Path given = Path.of(System.getenv("RENOVA_DOTNET"));
            candidates.add(Files.isDirectory(given) ? given.resolve(name) : given);
        }
        if (System.getenv("DOTNET_ROOT") != null) {
            candidates.add(Path.of(System.getenv("DOTNET_ROOT"), name));
        }
        for (String dir : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
            if (!dir.isBlank()) {
                candidates.add(Path.of(dir, name));
            }
        }
        candidates.add(Path.of(System.getProperty("user.home"), ".dotnet", name));
        for (String dir : List.of("/usr/share/dotnet", "/usr/lib/dotnet", "/usr/local/share/dotnet", "C:\\Program Files\\dotnet")) {
            candidates.add(Path.of(dir, name));
        }
        return candidates.stream().filter(Files::isExecutable).findFirst();
    }

    /** The feature versions of the installed SDKs ("8.0", "10.0"), oldest first; empty without an SDK. */
    public static List<String> sdks(Path dotnet) {
        try {
            Proc.Result result = Proc.run(List.of(dotnet.toString(), "--list-sdks"), dotnet.toAbsolutePath().getParent(), Duration.ofSeconds(60), environment(dotnet));
            List<String> versions = new ArrayList<>();
            Matcher m = Pattern.compile("(?m)^(\\d+\\.\\d+)\\.\\d+").matcher(result.output());
            while (m.find()) {
                if (!versions.contains(m.group(1))) {
                    versions.add(m.group(1));
                }
            }
            return versions;
        } catch (java.io.IOException e) {
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    /** No telemetry, no banner, English messages (they are parsed), and no build servers left running. */
    public static Map<String, String> environment(Path dotnet) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("DOTNET_CLI_TELEMETRY_OPTOUT", "1");
        env.put("DOTNET_NOLOGO", "1");
        env.put("DOTNET_CLI_UI_LANGUAGE", "en");
        env.put("DOTNET_ROOT", dotnet.toAbsolutePath().getParent().toString());
        env.put("MSBUILDDISABLENODEREUSE", "1");
        env.put("DOTNET_CLI_USE_MSBUILD_SERVER", "0");
        return env;
    }

    /** The modern .NET version the playbook moves projects to ("10.0"), or null when it names none. */
    public static String target(MigrationContext context) {
        return context.playbook().targets().get("dotnet");
    }
}
