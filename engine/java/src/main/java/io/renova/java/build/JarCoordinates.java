package io.renova.java.build;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Which published library a jar file is. A project that keeps its libraries as files does not say; knowing it
 * turns "a file called spring-core.jar" into a dependency that an upgrade can move to another version.
 */
public final class JarCoordinates {

    /** A library's Maven coordinates. */
    public record Gav(String groupId, String artifactId, String version) {
        public String coordinates() {
            return groupId + ":" + artifactId + ":" + version;
        }
    }

    private static final String CENTRAL = "https://repo.maven.apache.org/maven2/";
    private static final Pattern NAME = Pattern.compile("^(.*?)[-_](\\d[\\w.\\-]*)$");
    private static final Pattern FOUND = Pattern.compile("\"g\"\\s*:\\s*\"([^\"]+)\".*?\"a\"\\s*:\\s*\"([^\"]+)\".*?\"v\"\\s*:\\s*\"([^\"]+)\"",
            Pattern.DOTALL);

    private final boolean online;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    /** After a few failures in a row the network is taken as unavailable, so a hundred jars do not each wait. */
    private int failures;

    public JarCoordinates(boolean online) {
        this.online = online;
    }

    /** What the jar says about itself: the coordinates Maven wrote into it when it was built. */
    public static Optional<Gav> embedded(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            List<? extends ZipEntry> entries = zip.stream()
                    .filter(e -> e.getName().startsWith("META-INF/maven/") && e.getName().endsWith("/pom.properties")).toList();
            // A jar that bundles other libraries carries several; none of them describes the whole file.
            if (entries.size() != 1) {
                return Optional.empty();
            }
            Properties properties = new Properties();
            try (InputStream in = zip.getInputStream(entries.getFirst())) {
                properties.load(in);
            }
            String group = properties.getProperty("groupId");
            String artifact = properties.getProperty("artifactId");
            String version = properties.getProperty("version");
            return group == null || artifact == null || version == null ? Optional.empty()
                    : Optional.of(new Gav(group.strip(), artifact.strip(), version.strip()));
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * The published library this file is, when that can be shown: the jar names its coordinates, or Maven
     * Central knows a file with the same content, and the library can be fetched (it is in the local Maven
     * repository or on Central). Empty for a library nobody published, which stays a file.
     */
    public Optional<Gav> published(Path jar) {
        Optional<Gav> gav = embedded(jar);
        if (gav.isEmpty()) {
            gav = byContent(jar);
        }
        return gav.filter(this::fetchable);
    }

    /** "commons-lang-2.6.jar" → ("commons-lang", "2.6"); a name without a version gets version "0". */
    public static String[] nameAndVersion(Path jar) {
        String name = jar.getFileName().toString().replaceFirst("\\.jar$", "");
        Matcher m = NAME.matcher(name);
        String artifact = (m.matches() ? m.group(1) : name).replaceAll("[^A-Za-z0-9._-]", "-");
        return new String[] {artifact.isBlank() ? "library" : artifact, m.matches() ? m.group(2) : "0"};
    }

    /**
     * Asks Maven Central which library has this content. The search is only a hint (it has answered with a
     * neighbouring version), so a candidate counts when Central's own checksum for it is this file's.
     */
    private Optional<Gav> byContent(Path jar) {
        if (!usable()) {
            return Optional.empty();
        }
        try {
            String sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(jar)));
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://central.sonatype.com/solrsearch/select?q=1:%22" + sha1
                    + "%22&rows=1&wt=json")).timeout(Duration.ofSeconds(12)).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            failures = 0;
            Matcher m = FOUND.matcher(response.statusCode() == 200 ? response.body() : "");
            if (!m.find()) {
                return Optional.empty();
            }
            Gav hint = new Gav(m.group(1), m.group(2), m.group(3));
            Gav named = new Gav(hint.groupId(), hint.artifactId(), nameAndVersion(jar)[1]);
            for (Gav candidate : List.of(hint, named)) {
                if (sha1.equals(checksum(candidate))) {
                    return Optional.of(candidate);
                }
            }
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            failures++;
            return Optional.empty();
        }
    }

    /** Central's SHA-1 of the library's jar, or null when it has none. */
    private String checksum(Gav gav) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(CENTRAL + path(gav) + ".jar.sha1")).timeout(Duration.ofSeconds(12))
                .GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        // Some checksum files carry the file name after the digest.
        return response.statusCode() == 200 ? response.body().strip().split("\\s+")[0].toLowerCase(java.util.Locale.ROOT) : null;
    }

    private static String path(Gav gav) {
        return gav.groupId().replace('.', '/') + "/" + gav.artifactId() + "/" + gav.version() + "/" + gav.artifactId() + "-"
                + gav.version();
    }

    private boolean fetchable(Gav gav) {
        String path = path(gav);
        if (Files.isRegularFile(Path.of(System.getProperty("user.home"), ".m2", "repository").resolve(path + ".jar"))) {
            return true;
        }
        if (!usable()) {
            return false;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(CENTRAL + path + ".pom")).timeout(Duration.ofSeconds(12))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
            int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            failures = 0;
            return status == 200;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            failures++;
            return false;
        }
    }

    private boolean usable() {
        return online && failures < 3;
    }
}
