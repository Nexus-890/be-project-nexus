package com.group5.catalogservice.category.dto;

import java.util.List;
import java.util.UUID;

public record CategoryTreeResponse(
        UUID id,
        UUID parentId,
        String name,
        String slug,
        Short level,
        String path,
        List<CategoryTreeResponse> children
) {
}
