package com.group5.catalogservice.eventtest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EventTestRequest(

        @NotBlank(message = "aggregateId is required")
        @Size(max = 100, message = "aggregateId must not exceed 100 characters")
        String aggregateId,

        @NotBlank(message = "message is required")
        @Size(max = 500, message = "message must not exceed 500 characters")
        String message
) {
}
