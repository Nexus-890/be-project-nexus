package com.group5.catalogservice.category.dto;

import java.util.UUID;

public record CategoryResponse(
        UUID id,
        UUID parentId,
        String name,
        String slug,
        Short level,
        String path
) {
}
