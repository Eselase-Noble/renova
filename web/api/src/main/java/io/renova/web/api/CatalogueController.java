package io.renova.web.api;

import io.renova.core.engine.PluginRegistry;
import io.renova.core.playbook.Playbook;
import io.renova.core.playbook.Rule;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** What the server can migrate: installed playbooks. */
@RestController
@RequestMapping("/api/playbooks")
public class CatalogueController {

    private final PluginRegistry registry;

    public CatalogueController(PluginRegistry registry) {
        this.registry = registry;
    }

    public record PlaybookView(String id, String name, String ecosystem, String version, String description,
                               Map<String, String> targets, long rules, long guards, int knowledgeCards) {
    }

    @GetMapping
    public List<PlaybookView> playbooks() {
        return registry.playbooks().stream().map(CatalogueController::view).toList();
    }

    static PlaybookView view(Playbook p) {
        return new PlaybookView(p.id(), p.name(), p.ecosystem(), p.version(), p.description() == null ? null : p.description().strip(),
                p.targets(), p.rules().stream().filter(r -> !r.guard()).count(), p.rules().stream().filter(Rule::guard).count(),
                p.knowledge().size());
    }
}
