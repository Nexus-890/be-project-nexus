package com.group5.catalogservice.product.dto.response;

import java.util.UUID;

public record ImageResponse(UUID id, String imageUrl, Short sortOrder) {
}
