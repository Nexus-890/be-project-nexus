package com.group5.notificationservice.eventlog;

import com.group5.common_core.model.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import com.group5.notificationservice.eventlog.EventLogRepository.EventLogSummary;
import com.group5.notificationservice.eventlog.EventLogRepository.EventLogDetail;

@RestController
@RequestMapping("/api/v1/event-logs")
public class EventLogController {
    private final EventLogRepository repository;
    public EventLogController(EventLogRepository repository) { this.repository = repository; }

    @GetMapping
    @PreAuthorize("hasAuthority('EVENT_LOG.VIEW')")
    public ApiResponse<List<EventLogSummary>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String aggregateType,
            @RequestParam(required = false) String aggregateId,
            @RequestParam(required = false) String operatorId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "occurredAt,desc") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return repository.events(q, eventType, aggregateType, aggregateId, operatorId, from, to, sort, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('EVENT_LOG.VIEW_DETAIL')")
    public ApiResponse<EventLogDetail> detail(@PathVariable String id) { return ApiResponse.of(repository.detail(id)); }
}
