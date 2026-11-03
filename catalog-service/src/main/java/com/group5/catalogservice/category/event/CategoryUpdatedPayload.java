package com.group5.catalogservice.category.event;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record CategoryUpdatedPayload(
        UUID categoryId,
        String name,
        String slug,
        UUID parentId
) {
}
