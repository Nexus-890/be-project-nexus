package com.group5.notificationservice.eventlog.kafka.demo;

import com.group5.common_core.model.ApiResponse;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!kafka-smoke")
@RequestMapping("/internal/kafka-demo")
public class KafkaDemoMessageController {

    private final KafkaDemoMessageRepository repository;

    public KafkaDemoMessageController(KafkaDemoMessageRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/messages")
    public ApiResponse<List<KafkaDemoMessage>> messages() {
        return ApiResponse.list(repository.findLatest());
    }
}
