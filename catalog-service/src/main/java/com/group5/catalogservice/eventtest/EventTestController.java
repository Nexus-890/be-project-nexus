package com.group5.catalogservice.eventtest;

import com.group5.catalogservice.eventtest.dto.EventTestRequest;
import com.group5.catalogservice.eventtest.dto.EventTestResponse;
import com.group5.common_core.model.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile(EventTestService.PROFILE)
@RequestMapping("/internal/events")
public class EventTestController {

    private final EventTestService eventTestService;

    public EventTestController(EventTestService eventTestService) {
        this.eventTestService = eventTestService;
    }

    @PostMapping("/test")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<EventTestResponse> publish(@Valid @RequestBody EventTestRequest request) {
        return ApiResponse.of(eventTestService.publish(request));
    }
}
