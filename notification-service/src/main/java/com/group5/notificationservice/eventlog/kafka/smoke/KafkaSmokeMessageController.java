package com.group5.notificationservice.eventlog.kafka.smoke;

import com.group5.common_core.model.ApiResponse;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/kafka-smoke")
@Profile("kafka-smoke")
public class KafkaSmokeMessageController {

    private final KafkaSmokeMessageStore store;

    public KafkaSmokeMessageController(KafkaSmokeMessageStore store) {
        this.store = store;
    }

    @GetMapping("/messages")
    public ApiResponse<List<KafkaSmokeMessage>> messages() {
        return ApiResponse.list(store.snapshot());
    }
}
