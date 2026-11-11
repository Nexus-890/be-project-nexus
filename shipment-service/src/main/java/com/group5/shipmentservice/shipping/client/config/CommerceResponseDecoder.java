package com.group5.shipmentservice.shipping.client.config;

import feign.Response;
import feign.codec.DecodeException;
import feign.codec.Decoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.lang.reflect.Type;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Rejects malformed trusted responses instead of silently accepting coerced metadata. */
public final class CommerceResponseDecoder implements Decoder {
    private final ObjectMapper mapper;

    public CommerceResponseDecoder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Object decode(Response response, Type type) throws IOException {
        if (response.body() == null) throw invalidResponse(response);
        byte[] body = response.body().asInputStream().readAllBytes();
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode data = root.path("data");
            if (!root.isObject() || !root.path("code").isIntegralNumber()
                    || root.path("code").bigIntegerValue().signum() != 0 || !data.isObject()
                    || !validUuid(data.path("orderId")) || !validUuid(data.path("buyerId"))
                    || !data.path("orderVersion").isIntegralNumber() || !data.path("orderVersion").canConvertToLong()
                    || data.path("orderVersion").longValue() < 0
                    || !data.path("status").isString() || data.path("status").asString().isBlank()
                    || !data.path("paidAt").isString()
                    || !data.path("shippingFee").isNumber() || data.path("shippingFee").decimalValue().signum() < 0
                    || !data.path("shippingInfo").isObject()
                    || (data.hasNonNull("warehouseId") && !validUuid(data.path("warehouseId")))) {
                throw invalidResponse(response);
            }
            OffsetDateTime.parse(data.path("paidAt").asString());
            return mapper.convertValue(root, mapper.constructType(type));
        } catch (DecodeException error) {
            throw error;
        } catch (RuntimeException error) {
            throw invalidResponse(response);
        }
    }

    private static boolean validUuid(JsonNode node) {
        return node.isString() && UUID.fromString(node.asString()).toString().equals(node.asString());
    }

    private static DecodeException invalidResponse(Response response) {
        return new DecodeException(response.status(), "Invalid commerce shipping snapshot response", response.request());
    }
}
