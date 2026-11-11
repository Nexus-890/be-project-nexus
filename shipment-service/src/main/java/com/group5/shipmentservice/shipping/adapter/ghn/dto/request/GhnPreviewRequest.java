package com.group5.shipmentservice.shipping.adapter.ghn.dto.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Builder
public record GhnPreviewRequest(
        @JsonProperty("is_new_from_address") Boolean newFromAddress,
        @JsonProperty("from_province_name") String fromProvinceName,
        @JsonProperty("from_ward_name") String fromWardName,
        @JsonProperty("from_address") String fromAddress,
        @JsonProperty("is_new_to_address") Boolean newToAddress,
        @JsonProperty("to_province_name") String toProvinceName,
        @JsonProperty("to_ward_name") String toWardName,
        @JsonProperty("to_address") String toAddress,
        @JsonProperty("to_name") String toName,
        @JsonProperty("to_phone") String toPhone,
        @JsonProperty("payment_type_id") Integer paymentTypeId,
        @JsonProperty("required_note") String requiredNote,
        String content,
        @JsonProperty("service_type_id") Integer serviceTypeId,
        Integer weight,
        Integer length,
        Integer width,
        Integer height,
        @JsonProperty("insurance_value") Integer insuranceValue,
        @JsonProperty("cod_amount") Integer codAmount,
        List<GhnParcelItemRequest> items) {
}
