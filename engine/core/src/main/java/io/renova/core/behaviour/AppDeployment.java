package io.renova.core.behaviour;

import java.nio.file.Path;
import java.util.Map;

/**
 * How to run one version of an application in a container.
 *
 * @param image       container image with the runtime, e.g. "tomcat:9.0-jdk8"
 * @param mounts      host files or directories to mount read-only, to their path in the container
 * @param port        the port the application listens on inside the container
 * @param contextPath where the application is served, "" for the root
 * @param platform    short description for reports, e.g. "Java 8, Tomcat 9"
 */
public record AppDeployment(String image, Map<Path, String> mounts, int port, String contextPath, String platform) {

    public AppDeployment {
        mounts = Map.copyOf(mounts);
        contextPath = contextPath == null ? "" : contextPath;
    }
}
