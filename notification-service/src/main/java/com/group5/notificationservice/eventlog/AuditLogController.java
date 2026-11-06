package com.group5.notificationservice.eventlog;

import com.group5.common_core.model.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import com.group5.notificationservice.eventlog.EventLogRepository.AuditLog;

@RestController
@RequestMapping("/api/v1/audit-logs")
public class AuditLogController {
    private final EventLogRepository repository;
    public AuditLogController(EventLogRepository repository) { this.repository = repository; }

    @GetMapping
    @PreAuthorize("hasAuthority('AUDIT_LOG.VIEW')")
    public ApiResponse<List<AuditLog>> list(
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return repository.audits(actorId, action, entityType, entityId, from, to, page, size);
    }
}
