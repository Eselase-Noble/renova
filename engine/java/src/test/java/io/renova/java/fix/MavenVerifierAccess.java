package io.renova.java.fix;

import io.renova.core.engine.BuildError;

import java.nio.file.Path;
import java.util.List;

/** Exposes package-private parsing to tests in other packages. */
public final class MavenVerifierAccess {

    private MavenVerifierAccess() {
    }

    public static List<BuildError> parse(String output, Path workspace, Path buildRoot) {
        return MavenVerifier.parse(output, workspace, buildRoot);
    }
}
