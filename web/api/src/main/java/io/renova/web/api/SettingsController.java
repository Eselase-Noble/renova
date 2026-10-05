package io.renova.web.api;

import io.renova.web.settings.AiSettingsService;
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

/** AI provider settings, with the customer's own keys. Keys are write-only: responses show them masked. */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final AiSettingsService ai;

    public SettingsController(AiSettingsService ai) {
        this.ai = ai;
    }

    public record KeyRequest(String apiKey) {
    }

    public record CheckResult(String message) {
    }

    @GetMapping
    public AiSettingsService.View get() throws Exception {
        return ai.view();
    }

    /** Values: ai.provider, ai.model, ai.effort, rag.enabled, rag.budget; an empty value removes the setting. */
    @PutMapping
    public AiSettingsService.View update(@RequestBody Map<String, String> values) throws Exception {
        ai.update(values);
        return ai.view();
    }

    @PutMapping("/keys/{provider}")
    public AiSettingsService.View setKey(@PathVariable String provider, @RequestBody KeyRequest request) throws Exception {
        ai.setKey(provider, request.apiKey());
        return ai.view();
    }

    @DeleteMapping("/keys/{provider}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeKey(@PathVariable String provider) throws Exception {
        ai.removeKey(provider);
    }

    /** Verifies the key and model without generating anything (free). */
    @PostMapping("/check")
    public CheckResult check() throws Exception {
        return new CheckResult(ai.check());
    }
}
