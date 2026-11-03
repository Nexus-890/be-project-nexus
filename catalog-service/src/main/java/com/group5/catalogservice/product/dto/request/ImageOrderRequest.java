package com.group5.catalogservice.product.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record ImageOrderRequest(@NotNull @Size(max = 32767) List<@NotNull UUID> imageIds) {
}
