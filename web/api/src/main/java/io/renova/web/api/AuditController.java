package io.renova.web.api;

import io.renova.web.account.Access;
import io.renova.web.account.Role;
import io.renova.web.audit.AuditEvent;
import io.renova.web.audit.AuditLog;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The current organisation's audit log, for its admins. */
@RestController
@RequestMapping("/api/org/audit")
public class AuditController {

    private static final int MAX = 1000;

    private final AuditLog audit;
    private final Access access;

    public AuditController(AuditLog audit, Access access) {
        this.audit = audit;
        this.access = access;
    }

    /** @param area only entries of one area: auth, project, migration, settings, member or invitation */
    @GetMapping
    public List<AuditEvent> list(@RequestParam(required = false) String area, @RequestParam(defaultValue = "200") int limit,
                                 HttpServletRequest request) {
        Access.Caller caller = access.require(request, Role.ADMIN);
        return audit.recent(caller.organisationId(), area, Math.max(1, Math.min(limit, MAX)));
    }
}
