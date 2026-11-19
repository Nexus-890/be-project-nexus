package com.group5.shipmentservice.shipping.service;

import com.group5.common_core.exception.DomainException;
import com.group5.shipmentservice.shared.config.ShippingProperties;
import com.group5.shipmentservice.shared.exception.ShipmentErrorCode;
import com.group5.shipmentservice.shipping.adapter.CarrierAdapter;
import com.group5.shipmentservice.shipping.adapter.CarrierRegistry;
import com.group5.shipmentservice.shipping.adapter.model.CarrierQuoteRequest;
import com.group5.shipmentservice.shipping.adapter.model.CarrierAddress;
import com.group5.shipmentservice.shipping.adapter.model.PickupAddress;
import com.group5.shipmentservice.shipping.dto.request.ShippingQuoteRequest;
import com.group5.shipmentservice.shipping.dto.response.ShippingQuoteResponse;
import com.group5.shipmentservice.shipping.resilience.CarrierExecutor;
import com.group5.shipmentservice.shipping.resilience.ShippingDeadline;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Service
public class ShippingQuoteService {
    private final ShippingProperties properties;
    private final CarrierRegistry carriers;
    private final ShippingAddressService addresses;
    private final ShippingCatalogService catalog;
    private final CarrierExecutor executor;

    public ShippingQuoteService(ShippingProperties properties, CarrierRegistry carriers,
                                ShippingAddressService addresses, ShippingCatalogService catalog,
                                CarrierExecutor executor) {
        this.properties = properties;
        this.carriers = carriers;
        this.addresses = addresses;
        this.catalog = catalog;
        this.executor = executor;
    }

    public List<ShippingQuoteResponse> quote(ShippingQuoteRequest request) {
        ShippingDeadline deadline = ShippingDeadline.after(Duration.ofMillis(properties.apiTimeoutMs()));
        PickupAddress pickup = resolvePickup(request.warehouseId());
        CarrierAdapter adapter = carriers.getDefaultAdapter();
        return executor.execute(adapter.code(), deadline, remaining -> {
            var destination = request.toAddress();
            CarrierAddress toAddress = addresses.resolveAddress(adapter, destination.provinceCode(),
                    destination.wardCode(), destination.line1(), remaining);
            CarrierAddress fromAddress = resolvePickupAddress(adapter, pickup, remaining);
            BigDecimal subtotal = catalog.getSubtotal(request.items(), remaining);
            BigDecimal insuranceValue = request.insuranceValue() == null ? BigDecimal.ZERO : request.insuranceValue();
            List<ShippingQuoteResponse> quotes = adapter.quote(
                    new CarrierQuoteRequest(fromAddress, toAddress, request.parcel(), insuranceValue,
                            destination.recipientName(), destination.recipientPhone(), shipmentContent(request)), remaining);
            return applyShippingPolicy(adapter, quotes, subtotal, request.applyFreeShipping());
        });
    }

    private String shipmentContent(ShippingQuoteRequest request) {
        int quantity = request.items().stream().mapToInt(item -> item.quantity()).sum();
        long skuCount = request.items().stream().map(item -> item.skuId()).distinct().count();
        return "Đơn hàng gồm " + quantity + " sản phẩm thuộc " + skuCount + " SKU, đóng trong 1 kiện";
    }

    private List<ShippingQuoteResponse> applyShippingPolicy(CarrierAdapter adapter,
                                                           List<ShippingQuoteResponse> quotes, BigDecimal subtotal,
                                                           boolean applyFreeShipping) {
        if (quotes == null) {
            throw new DomainException(ShipmentErrorCode.INVALID_CARRIER_RESPONSE);
        }
        if (quotes.isEmpty()) {
            throw new DomainException(ShipmentErrorCode.NO_SERVICE);
        }
        if (quotes.stream().anyMatch(quote -> quote == null || quote.fee() == null || quote.fee().signum() < 0
                || quote.estimatedDeliveryAt() == null || !adapter.code().equals(quote.carrier())
                || quote.serviceType() == null)) {
            throw new DomainException(ShipmentErrorCode.INVALID_CARRIER_RESPONSE);
        }
        if (applyFreeShipping && subtotal.compareTo(properties.freeShippingThreshold()) >= 0) {
            return quotes.stream().map(quote -> new ShippingQuoteResponse(quote.carrier(), quote.serviceType(),
                    BigDecimal.ZERO, quote.estimatedDeliveryAt())).toList();
        }
        return quotes;
    }

    private PickupAddress resolvePickup(UUID requestedWarehouse) {
        UUID warehouseId = requestedWarehouse == null ? properties.defaultWarehouseId() : requestedWarehouse;
        if (warehouseId == null) {
            throw new DomainException(ShipmentErrorCode.CARRIER_CONFIGURATION_ERROR);
        }
        PickupAddress pickup = properties.pickups().get(warehouseId);
        if (pickup == null) {
            pickup = properties.platformPickup();
        }
        if (pickup == null) {
            throw new DomainException(requestedWarehouse == null ? ShipmentErrorCode.CARRIER_CONFIGURATION_ERROR
                    : ShipmentErrorCode.WAREHOUSE_NOT_FOUND);
        }
        if (pickup.provinceCode() == null || !pickup.provinceCode().matches("[1-9][0-9]{0,8}")
                || pickup.wardCode() == null || !pickup.wardCode().matches("[1-9][0-9]{0,8}")
                || pickup.addressLine() == null || pickup.addressLine().isBlank() || pickup.addressLine().length() > 255) {
            throw new DomainException(ShipmentErrorCode.CARRIER_CONFIGURATION_ERROR);
        }
        return pickup;
    }

    private CarrierAddress resolvePickupAddress(CarrierAdapter adapter, PickupAddress pickup, ShippingDeadline deadline) {
        try {
            return addresses.resolveAddress(adapter, pickup.provinceCode(), pickup.wardCode(), pickup.addressLine(), deadline);
        } catch (DomainException error) {
            if (ShipmentErrorCode.INVALID_ADDRESS.code().equals(error.getErrorCode())) {
                throw new DomainException(ShipmentErrorCode.CARRIER_CONFIGURATION_ERROR);
            }
            throw error;
        }
    }
}
