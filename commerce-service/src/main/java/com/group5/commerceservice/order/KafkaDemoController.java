package com.group5.commerceservice.order;

import com.group5.commerceservice.order.dto.KafkaDemoRequest;
import com.group5.commerceservice.order.dto.KafkaDemoResult;
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
@Profile("!kafka-smoke")
@RequestMapping("/internal/kafka-demo/messages")
public class KafkaDemoController {
    private final KafkaDemoPublisher publisher;

    public KafkaDemoController(KafkaDemoPublisher publisher) {
        this.publisher = publisher;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<KafkaDemoResult> publish(@Valid @RequestBody KafkaDemoRequest request) {
        return ApiResponse.of(publisher.publish(request));
    }
}
