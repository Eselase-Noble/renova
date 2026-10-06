package io.renova.web.api;

import io.renova.web.account.Access;
import io.renova.web.account.Role;
import io.renova.web.audit.AuditLog;
import io.renova.web.settings.AiSettingsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The current organisation's AI settings, with its own keys. Anyone in the organisation may see them (keys
 * masked); admins change them. Keys are write-only.
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final AiSettingsService ai;
    private final Access access;
    private final AuditLog audit;

    public SettingsController(AiSettingsService ai, Access access, AuditLog audit) {
        this.ai = ai;
        this.access = access;
        this.audit = audit;
    }

    public record KeyRequest(String apiKey) {
    }

    public record CheckResult(String message) {
    }

    @GetMapping
    public AiSettingsService.View get(HttpServletRequest request) throws Exception {
        return ai.view(access.caller(request).organisationId());
    }

    /** Values: ai.provider, ai.model, ai.effort, rag.enabled, PROVIDER.baseUrl; an empty value clears it. */
    @PutMapping
    public AiSettingsService.View update(@RequestBody Map<String, String> values, HttpServletRequest request) throws Exception {
        Access.Caller caller = access.require(request, Role.ADMIN);
        String org = caller.organisationId();
        ai.update(org, values);
        // Setting names and values: none of them is a secret (keys have their own endpoint).
        audit.record(caller, "settings.changed", "AI settings", values.entrySet().stream()
                .map(e -> e.getKey() + " = " + (e.getValue() == null || e.getValue().isBlank() ? "(default)" : e.getValue()))
                .sorted().collect(java.util.stream.Collectors.joining(", ")));
        return ai.view(org);
    }

    @PutMapping("/keys/{provider}")
    public AiSettingsService.View setKey(@PathVariable String provider, @RequestBody KeyRequest body, HttpServletRequest request)
            throws Exception {
        Access.Caller caller = access.require(request, Role.ADMIN);
        String org = caller.organisationId();
        ai.setKey(org, provider, body.apiKey());
        audit.record(caller, "settings.key_set", provider, "API key saved");
        return ai.view(org);
    }

    @DeleteMapping("/keys/{provider}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeKey(@PathVariable String provider, HttpServletRequest request) throws Exception {
        Access.Caller caller = access.require(request, Role.ADMIN);
        ai.removeKey(caller.organisationId(), provider);
        audit.record(caller, "settings.key_removed", provider, "API key removed");
    }

    /** Verifies the key and model without generating anything (free). */
    @PostMapping("/check")
    public CheckResult check(HttpServletRequest request) throws Exception {
        return new CheckResult(ai.check(access.require(request, Role.MEMBER).organisationId()));
    }
}
