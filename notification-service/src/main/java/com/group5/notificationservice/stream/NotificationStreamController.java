package com.group5.notificationservice.stream;

import com.group5.common_core.exception.DomainException;
import com.group5.common_core.model.ProtocolResponse;
import com.group5.common_security.CurrentUser;
import com.group5.notificationservice.shared.exception.NotificationErrorCode;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationStreamController {
    private final NotificationStreamRegistry streams;
    public NotificationStreamController(NotificationStreamRegistry streams) { this.streams = streams; }
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAuthority('NOTIFICATION.VIEW')")
    @ProtocolResponse("Authenticated Server-Sent Events: notifications snapshot and heartbeat; reconnect within 30 seconds")
    public ResponseEntity<SseEmitter> stream(CurrentUser currentUser, @AuthenticationPrincipal Jwt jwt) {
        if (currentUser == null || currentUser.id() == null || jwt == null)
            throw new DomainException(NotificationErrorCode.UNAUTHENTICATED);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform")
                .header("X-Accel-Buffering", "no")
                .body(streams.connect(currentUser.id(), jwt.getExpiresAt()));
    }
}
