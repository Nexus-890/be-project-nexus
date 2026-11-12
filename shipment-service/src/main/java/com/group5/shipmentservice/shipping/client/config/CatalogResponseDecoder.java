package com.group5.shipmentservice.shipping.client.config;

import feign.Response;
import feign.codec.DecodeException;
import feign.codec.Decoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.UUID;

public final class CatalogResponseDecoder implements Decoder {
    private final ObjectMapper mapper;
    //đọc và kiểm tra JSON response
    public CatalogResponseDecoder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Object decode(Response response, Type type) throws IOException {
        if (response.body() == null) {
            throw invalidResponse(response);
        }
        byte[] body = response.body().asInputStream().readAllBytes();
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode data = root.path("data");
            JsonNode price = data.path("price");
            if (!root.isObject() || !root.path("code").isIntegralNumber() || root.path("code").bigIntegerValue().signum() != 0
                    || !data.isObject() || !data.path("id").isString()
                    || !price.isNumber() || price.decimalValue().signum() < 0
                    || !data.path("status").isString() || data.path("status").asString().isBlank()) {
                throw invalidResponse(response);
            }
            String id = data.path("id").asString();
            if (!UUID.fromString(id).toString().equals(id)) {
                throw invalidResponse(response);
            }
            return mapper.convertValue(root, mapper.constructType(type));
        } catch (DecodeException error) {
            throw error;
        } catch (RuntimeException error) {
            throw invalidResponse(response);
        }
    }

    private DecodeException invalidResponse(Response response) {
        return new DecodeException(response.status(), "Phản hồi catalog không hợp lệ", response.request());
    }
}
