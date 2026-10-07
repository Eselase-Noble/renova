package io.renova.dotnet;

import io.renova.core.scan.ScanContext;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Which files of a .NET project are its own: not build output, not restored packages. */
public final class Sources {

    /** Folders MSBuild and NuGet fill; anything in them is produced, not written. */
    public static final Set<String> PRODUCED = Set.of("bin", "obj", "packages", ".vs", "TestResults");
    public static final List<String> PROJECT_FILES = List.of("**/*.csproj", "**/*.vbproj");
    public static final List<String> CODE = List.of("**/*.cs", "**/*.vb");

    private Sources() {
    }

    public static boolean produced(Path relative) {
        for (Path part : relative) {
            if (PRODUCED.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    public static List<Path> files(ScanContext ctx, List<String> globs) {
        return ctx.files(globs).stream().filter(f -> !produced(f)).toList();
    }
}
