package io.renova.core.behaviour;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * How to run one version of an application in a container.
 *
 * @param image       container image with the runtime, e.g. "tomcat:9.0-jdk8"
 * @param mounts      host files or directories to mount read-only, to their path in the container
 * @param port        the port the application listens on inside the container
 * @param contextPath where the application is served, "" for the root
 * @param platform    short description for reports, e.g. "Java 8, Tomcat 9"
 * @param environment environment variables for the container
 * @param command     what the container runs, e.g. "java -jar /app/app.jar"; empty for the image's own command
 */
public record AppDeployment(String image, Map<Path, String> mounts, int port, String contextPath, String platform,
                            Map<String, String> environment, List<String> command) {

    public AppDeployment {
        mounts = Map.copyOf(mounts);
        contextPath = contextPath == null ? "" : contextPath;
        environment = environment == null ? Map.of() : Map.copyOf(environment);
        command = command == null ? List.of() : List.copyOf(command);
    }

    public AppDeployment(String image, Map<Path, String> mounts, int port, String contextPath, String platform) {
        this(image, mounts, port, contextPath, platform, Map.of(), List.of());
    }

    public AppDeployment withEnvironment(Map<String, String> more) {
        Map<String, String> env = new java.util.LinkedHashMap<>(environment);
        env.putAll(more);
        return new AppDeployment(image, mounts, port, contextPath, platform, env, command);
    }

    public AppDeployment withCommand(List<String> command) {
        return new AppDeployment(image, mounts, port, contextPath, platform, environment, command);
    }
}
