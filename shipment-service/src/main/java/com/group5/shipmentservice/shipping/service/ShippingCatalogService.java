package com.group5.shipmentservice.shipping.service;

import com.group5.common_core.exception.DomainException;
import com.group5.common_core.model.ApiResponse;
import com.group5.shipmentservice.shared.exception.ShipmentErrorCode;
import com.group5.shipmentservice.shipping.client.CatalogClient;
import com.group5.shipmentservice.shipping.client.config.CatalogDeadlineOptions;
import com.group5.shipmentservice.shipping.dto.request.ShippingItemRequest;
import com.group5.shipmentservice.shipping.dto.response.CatalogSkuResponse;
import com.group5.shipmentservice.shipping.resilience.CarrierExecutor;
import com.group5.shipmentservice.shipping.resilience.ShippingDeadline;
import feign.FeignException;
import feign.codec.DecodeException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ShippingCatalogService {
    private final CatalogClient catalog;
    private final CarrierExecutor executor;

    public ShippingCatalogService(CatalogClient catalog, CarrierExecutor executor) {
        this.catalog = catalog;
        this.executor = executor;
    }

    public BigDecimal getSubtotal(List<ShippingItemRequest> items, ShippingDeadline deadline) {
        return executor.execute("CATALOG", deadline, remaining -> {
            Map<UUID, BigDecimal> prices = new HashMap<>();
            BigDecimal subtotal = BigDecimal.ZERO;
            for (ShippingItemRequest item : items) {
                BigDecimal price = prices.computeIfAbsent(item.skuId(), skuId -> readPrice(skuId, remaining));
                subtotal = subtotal.add(price.multiply(BigDecimal.valueOf(item.quantity())));
            }
            return subtotal;
        });
    }

    private BigDecimal readPrice(UUID skuId, ShippingDeadline deadline) {
        ApiResponse<CatalogSkuResponse> response;
        try {
            response = catalog.getSkuById(skuId, new CatalogDeadlineOptions(deadline));
        } catch (FeignException.NotFound exception) {
            throw new DomainException(ShipmentErrorCode.SKU_NOT_FOUND);
        } catch (DecodeException exception) {
            throw new DomainException(ShipmentErrorCode.INVALID_CATALOG_RESPONSE);
        } catch (FeignException exception) {
            throw new DomainException(ShipmentErrorCode.CATALOG_UNAVAILABLE);
        }
        if (response == null || response.code() != 0 || response.data() == null) {
            throw new DomainException(ShipmentErrorCode.INVALID_CATALOG_RESPONSE);
        }
        CatalogSkuResponse sku = response.data();
        if (!skuId.equals(sku.id()) || sku.price() == null || sku.price().signum() < 0
                || sku.status() == null || sku.status().isBlank()) {
            throw new DomainException(ShipmentErrorCode.INVALID_CATALOG_RESPONSE);
        }
        if (!"ACTIVE".equals(sku.status())) {
            throw new DomainException(ShipmentErrorCode.SKU_NOT_FOUND);
        }
        return sku.price();
    }
}
