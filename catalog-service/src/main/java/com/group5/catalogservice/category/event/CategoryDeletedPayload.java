package com.group5.catalogservice.category.event;

import java.util.UUID;

public record CategoryDeletedPayload(UUID categoryId) {
}
