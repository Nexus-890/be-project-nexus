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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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
        PickupAddress pickup = request.pickupAddress() == null ? resolvePickup(request.warehouseId())
                : new PickupAddress(request.pickupAddress().provinceCode(), request.pickupAddress().wardCode(),
                request.pickupAddress().line1().strip());
        List<CarrierAdapter> offered = carriers.getQuoteAdapters();
        if (offered.isEmpty()) offered = List.of(carriers.getDefaultAdapter());
        AtomicReference<BigDecimal> subtotal = new AtomicReference<>();
        List<ShippingQuoteResponse> options = new ArrayList<>();
        DomainException firstFailure = null;
        for (CarrierAdapter adapter : offered) {
            try {
                options.addAll(quoteWith(adapter, request, pickup, subtotal, deadline));
            } catch (DomainException failure) {
                if (offered.size() == 1) throw failure;
                if (firstFailure == null) firstFailure = failure;
            }
        }
        if (options.isEmpty()) throw firstFailure;
        options.sort(order());
        List<ShippingQuoteResponse> ranked = new ArrayList<>();
        for (int index = 0; index < options.size(); index++) {
            ShippingQuoteResponse option = options.get(index);
            ranked.add(option.withChoice(carrierName(offered, option.carrier()), index == 0));
        }
        return ranked;
    }

    private List<ShippingQuoteResponse> quoteWith(CarrierAdapter adapter, ShippingQuoteRequest request, PickupAddress pickup,
                                                  AtomicReference<BigDecimal> subtotal, ShippingDeadline deadline) {
        return executor.execute(adapter.code(), deadline, remaining -> {
            var destination = request.toAddress();
            CarrierAddress toAddress = addresses.resolveAddress(adapter, destination.provinceCode(),
                    destination.wardCode(), destination.line1(), remaining);
            CarrierAddress fromAddress = resolvePickupAddress(adapter, pickup, remaining);
            if (subtotal.get() == null) subtotal.set(catalog.getSubtotal(request.items(), remaining));
            BigDecimal insuranceValue = request.insuranceValue() == null ? BigDecimal.ZERO : request.insuranceValue();
            List<ShippingQuoteResponse> quotes = adapter.quote(
                    new CarrierQuoteRequest(fromAddress, toAddress, request.parcel(), insuranceValue,
                            destination.recipientName(), destination.recipientPhone(), shipmentContent(request)), remaining);
            return applyShippingPolicy(adapter, quotes, subtotal.get(), request.applyFreeShipping());
        });
    }

    private Comparator<ShippingQuoteResponse> order() {
        Comparator<ShippingQuoteResponse> byFee = Comparator.comparing(ShippingQuoteResponse::fee);
        Comparator<ShippingQuoteResponse> byArrival = Comparator.comparing(ShippingQuoteResponse::estimatedDeliveryAt);
        Comparator<ShippingQuoteResponse> rule = properties.carrierStrategy() == ShippingProperties.CarrierStrategy.FASTEST
                ? byArrival.thenComparing(byFee) : byFee.thenComparing(byArrival);
        return rule.thenComparing(ShippingQuoteResponse::carrier);
    }

    private static String carrierName(List<CarrierAdapter> offered, String code) {
        return offered.stream().filter(adapter -> adapter.code().equals(code)).map(CarrierAdapter::name)
                .findFirst().orElse(code);
    }

    private String shipmentContent(ShippingQuoteRequest request) {
        int quantity = request.items().stream().mapToInt(item -> item.quantity()).sum();
        long skuCount = request.items().stream().map(item -> item.skuId()).distinct().count();
        return "Order of " + quantity + " item(s) across " + skuCount + " SKU(s), packed in 1 parcel";
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
