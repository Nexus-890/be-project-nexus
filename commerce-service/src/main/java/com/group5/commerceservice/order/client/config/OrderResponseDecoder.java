package com.group5.commerceservice.order.client.config;

import feign.Response;
import feign.codec.DecodeException;
import feign.codec.Decoder;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.lang.reflect.Type;

/** ApiResponse chuẩn hóa field null nên kiểm envelope trước khi chuyển sang DTO. */
public final class OrderResponseDecoder implements Decoder {
    private final ObjectMapper mapper;

    public OrderResponseDecoder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Object decode(Response response, Type type) throws IOException {
        if (response.body() == null) throw invalid(response);
        try {
            var root = mapper.readTree(response.body().asInputStream().readAllBytes());
            var payload = mapper.constructType(type).containedType(0);
            if (payload != null && payload.hasRawClass(com.group5.commerceservice.order.client.InventoryReservationHttpClient.Pickup.class)) {
                if (!root.isObject() || !root.path("code").isIntegralNumber() || !root.path("message").isString()
                        || root.path("data").isMissingNode()
                        || !(root.path("data").isNull() || root.path("data").isObject())) {
                    throw invalid(response);
                }
                return mapper.convertValue(root, mapper.constructType(type));
            }
            if (!root.isObject() || !root.path("code").isIntegralNumber()
                    || root.path("code").bigIntegerValue().signum() != 0 || !root.path("message").isString()
                    || root.path("data").isMissingNode() || root.path("data").isNull()) {
                throw invalid(response);
            }
            JsonNode data = root.path("data");
            if (data.isArray()) {
                var dataType = mapper.constructType(type).containedType(0);
                boolean linkedReservations = dataType != null && dataType.getContentType() != null
                        && dataType.getContentType().hasRawClass(com.group5.commerceservice.order.client.InventoryReservationHttpClient.LinkedReservation.class);
                for (JsonNode quote : data) {
                    if (linkedReservations) {
                        if (!quote.isObject() || !quote.path("reservationId").isString()
                                || !quote.path("warehouseId").isString() || !quote.path("status").isString()) throw invalid(response);
                    } else if (!quote.isObject() || !quote.path("fee").isNumber()
                            || !quote.path("carrier").isString() || !quote.path("serviceType").isString()
                            || !quote.path("estimatedDeliveryAt").isString()) throw invalid(response);
                }
            } else if (!data.isObject() || !data.path("warehouseId").isString()
                    || !data.path("expiresAt").isString() || !data.path("reservationIds").isArray()) {
                throw invalid(response);
            } else {
                for (JsonNode id : data.path("reservationIds")) if (!id.isString()) throw invalid(response);
                JsonNode orders = data.path("orders");
                if (!orders.isMissingNode() && !orders.isNull()) {
                    if (!orders.isArray()) throw invalid(response);
                    for (JsonNode order : orders) {
                        if (!order.isObject() || !order.path("orderId").isString()
                                || !order.path("warehouseId").isString()) throw invalid(response);
                    }
                }
            }
            return mapper.convertValue(root, mapper.constructType(type));
        } catch (DecodeException error) {
            throw error;
        } catch (RuntimeException error) {
            throw invalid(response);
        }
    }

    private DecodeException invalid(Response response) {
        return new DecodeException(response.status(), "Invalid checkout dependency response", response.request());
    }
}
