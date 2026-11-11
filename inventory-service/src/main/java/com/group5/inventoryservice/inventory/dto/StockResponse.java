package com.group5.inventoryservice.inventory.dto;

import java.util.UUID;

public record StockResponse(UUID skuId, int available) {
}
