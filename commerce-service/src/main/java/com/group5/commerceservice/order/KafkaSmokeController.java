package com.group5.commerceservice.order;

import com.group5.commerceservice.order.dto.KafkaSmokeRequest;
import com.group5.commerceservice.order.dto.KafkaSmokeResult;
import jakarta.validation.Valid;
import com.group5.common_core.model.ApiResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("kafka-smoke")
@RequestMapping("/internal/kafka-smoke/messages")
public class KafkaSmokeController {
    private final KafkaSmokePublisher publisher;

    public KafkaSmokeController(KafkaSmokePublisher publisher) {
        this.publisher = publisher;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<KafkaSmokeResult> publish(@Valid @RequestBody KafkaSmokeRequest request) {
        return ApiResponse.of(publisher.publish(request));
    }
}
