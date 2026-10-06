package io.renova.web.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.renova.web.account.Access;
import io.renova.web.account.User;
import io.renova.web.store.DataStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Each organisation's audit log: one JSON object per line in {@code audit/ORGANISATION.jsonl}, only ever
 * appended to. Entries name who did what to what; keys, passwords and invitation tokens are never written.
 */
@Component
public class AuditLog {

    private final Path dir;
    private final ObjectMapper json = new ObjectMapper();

    public AuditLog(@Value("${renova.data-dir}") Path dataDir) throws IOException {
        this.dir = Files.createDirectories(dataDir.toAbsolutePath().normalize().resolve("audit"));
    }

    public void record(Access.Caller caller, String action, String target, String detail) {
        record(caller.organisationId(), caller.user(), action, target, detail);
    }

    public synchronized void record(String organisationId, User actor, String action, String target, String detail) {
        if (!DataStore.validId(organisationId)) {
            return;
        }
        AuditEvent event = new AuditEvent(UUID.randomUUID().toString().substring(0, 12), Instant.now().toString(), organisationId,
                actor == null ? null : actor.id(), actor == null ? "Renova" : actor.name(), action, target, detail);
        try {
            Files.writeString(dir.resolve(organisationId + ".jsonl"), json.writeValueAsString(event) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The newest {@code limit} entries, newest first, optionally only those whose action starts with {@code area}. */
    public synchronized List<AuditEvent> recent(String organisationId, String area, int limit) {
        Path file = dir.resolve(organisationId + ".jsonl");
        if (!DataStore.validId(organisationId) || !Files.exists(file)) {
            return List.of();
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<AuditEvent> events = new ArrayList<>();
            for (int i = lines.size() - 1; i >= 0 && events.size() < limit; i--) {
                if (lines.get(i).isBlank()) {
                    continue;
                }
                AuditEvent event = json.readValue(lines.get(i), AuditEvent.class);
                if (area == null || area.isBlank() || event.action().startsWith(area + ".")) {
                    events.add(event);
                }
            }
            return events;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
