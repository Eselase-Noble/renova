package io.renova.web.api;

import io.renova.web.account.Access;
import io.renova.web.account.Role;
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

    public SettingsController(AiSettingsService ai, Access access) {
        this.ai = ai;
        this.access = access;
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
        String org = access.require(request, Role.ADMIN).organisationId();
        ai.update(org, values);
        return ai.view(org);
    }

    @PutMapping("/keys/{provider}")
    public AiSettingsService.View setKey(@PathVariable String provider, @RequestBody KeyRequest body, HttpServletRequest request)
            throws Exception {
        String org = access.require(request, Role.ADMIN).organisationId();
        ai.setKey(org, provider, body.apiKey());
        return ai.view(org);
    }

    @DeleteMapping("/keys/{provider}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeKey(@PathVariable String provider, HttpServletRequest request) throws Exception {
        ai.removeKey(access.require(request, Role.ADMIN).organisationId(), provider);
    }

    /** Verifies the key and model without generating anything (free). */
    @PostMapping("/check")
    public CheckResult check(HttpServletRequest request) throws Exception {
        return new CheckResult(ai.check(access.require(request, Role.MEMBER).organisationId()));
    }
}
