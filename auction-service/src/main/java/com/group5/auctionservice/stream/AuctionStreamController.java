package com.group5.auctionservice.stream;

import com.group5.common_core.model.ProtocolResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auctions")
public class AuctionStreamController {
    private final AuctionStreamRegistry streams;

    /** Mở stream công khai; kiểm tra tồn tại và giới hạn trước khi ghi header SSE. */
    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ProtocolResponse("Server-Sent Events: state and heartbeat")
    public ResponseEntity<SseEmitter> stream(@PathVariable UUID id) {
        SseEmitter emitter = streams.connect(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform")
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }
}