package com.group5.shipmentservice.shipping.service;

import com.group5.common_core.exception.DomainException;
import com.group5.shipmentservice.shared.exception.ShipmentErrorCode;
import com.group5.shipmentservice.shipping.client.CommerceOrderClient;
import com.group5.shipmentservice.shipping.client.config.CommerceDeadlineOptions;
import com.group5.shipmentservice.shipping.dto.response.PaidOrderShippingResponse;
import com.group5.shipmentservice.shipping.event.payload.OrderPaidSnapshot;
import com.group5.shipmentservice.shipping.resilience.CarrierExecutor;
import feign.FeignException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;
import java.math.RoundingMode;

@Service
public class PaidOrderSnapshotService {
    private final CommerceOrderClient commerce;
    private final CarrierExecutor executor;
    private final JsonMapper mapper;

    public PaidOrderSnapshotService(CommerceOrderClient commerce, CarrierExecutor executor, JsonMapper mapper) {
        this.commerce = commerce;
        this.executor = executor;
        this.mapper = mapper;
    }

    public OrderPaidSnapshot load(UUID orderId) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwt) || !jwt.isAuthenticated()) {
            throw new DomainException(ShipmentErrorCode.UNAUTHENTICATED);
        }
        PaidOrderShippingResponse response;
        try {
            response = executor.execute("COMMERCE", deadline -> {
                try {
                    var envelope = commerce.getPaidOrder(orderId, "Bearer " + jwt.getToken().getTokenValue(),
                            new CommerceDeadlineOptions(deadline));
                    if (envelope == null || envelope.data() == null) invalid();
                    return envelope.data();
                } catch (FeignException error) {
                    if (error.status() >= 500 || error.status() < 0) {
                        throw new DomainException(ShipmentErrorCode.COMMERCE_UNAVAILABLE);
                    }
                    throw error;
                }
            });
        } catch (FeignException error) {
            throw new DomainException(switch (error.status()) {
                case 401 -> ShipmentErrorCode.UNAUTHENTICATED;
                case 403 -> ShipmentErrorCode.FORBIDDEN;
                case 404 -> ShipmentErrorCode.ORDER_NOT_FOUND;
                case 409 -> upstreamInvalidSnapshot(error)
                        ? ShipmentErrorCode.INVALID_ORDER_SNAPSHOT : ShipmentErrorCode.ORDER_NOT_PAID;
                case 422 -> ShipmentErrorCode.INVALID_ORDER_SNAPSHOT;
                default -> error.status() >= 500 || error.status() < 0
                        ? ShipmentErrorCode.COMMERCE_UNAVAILABLE : ShipmentErrorCode.INVALID_ORDER_SNAPSHOT;
            });
        } catch (DomainException error) {
            if (ShipmentErrorCode.INVALID_ORDER_SNAPSHOT.code().equals(error.getErrorCode())) throw error;
            throw new DomainException(ShipmentErrorCode.COMMERCE_UNAVAILABLE);
        } catch (RuntimeException error) {
            throw new DomainException(ShipmentErrorCode.INVALID_ORDER_SNAPSHOT);
        }
        try {
            if (response == null || !orderId.equals(response.orderId()) || response.buyerId() == null
                    || response.sellerId() == null
                    || response.orderVersion() < 0 || response.paidAt() == null
                    || response.shippingFee() == null || response.shippingFee().signum() < 0
                    || response.shippingFee().setScale(2, RoundingMode.UNNECESSARY).precision() > 19) invalid();
            if (!"PAID".equals(response.status())) throw new DomainException(ShipmentErrorCode.ORDER_NOT_PAID);
            JsonNode info = mapper.valueToTree(response.shippingInfo());
            required(info.path("recipient"), "name", 150);
            required(info.path("recipient"), "phone", 20);
            required(info.path("recipient").path("address"), "line1", 500);
            for (String field : new String[]{"carrier", "serviceType"}) {
                if (info.hasNonNull(field) && (!info.path(field).isString()
                        || !info.path(field).asString().matches("\\S{1,40}"))) invalid();
            }
            if (!info.path("items").isArray() || info.path("items").isEmpty()) invalid();
            for (JsonNode item : info.path("items")) {
                UUID.fromString(item.path("skuId").asString());
                required(item, "productName", 255);
                if (!item.path("quantity").isIntegralNumber() || !item.path("quantity").canConvertToInt()
                        || item.path("quantity").asInt() <= 0) invalid();
            }
            return new OrderPaidSnapshot(response.orderId(), response.buyerId(), response.warehouseId(),
                    response.shippingFee(), response.shippingInfo(), response.orderVersion(), response.sellerId());
        } catch (DomainException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new DomainException(ShipmentErrorCode.INVALID_ORDER_SNAPSHOT);
        }
    }

    private boolean upstreamInvalidSnapshot(FeignException error) {
        try {
            return "COMMERCE_INVALID_SHIPPING_SNAPSHOT".equals(mapper.readTree(error.contentUTF8()).path("code").asString());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static void required(JsonNode object, String field, int max) {
        if (!object.path(field).isString() || object.path(field).asString().isBlank()
                || object.path(field).asString().length() > max) invalid();
    }

    private static void invalid() { throw new DomainException(ShipmentErrorCode.INVALID_ORDER_SNAPSHOT); }
}
