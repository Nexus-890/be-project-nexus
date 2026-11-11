package com.group5.shipmentservice.shipping.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

public record CatalogSkuResponse(UUID id, BigDecimal price, String status) {
}
