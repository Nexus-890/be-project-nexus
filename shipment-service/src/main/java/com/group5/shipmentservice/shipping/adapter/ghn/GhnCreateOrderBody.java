package com.group5.shipmentservice.shipping.adapter.ghn;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.group5.shipmentservice.shipping.adapter.ghn.dto.request.GhnParcelItemRequest;
import lombok.Builder;

import java.util.List;

/** GHN's current two-level administrative address format (no district). */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Builder(toBuilder = true)
public record GhnCreateOrderBody(
        @JsonProperty("client_order_code") String clientOrderCode,
        @JsonProperty("is_new_from_address") boolean newFromAddress,
        @JsonProperty("from_province_name") String fromProvinceName,
        @JsonProperty("from_ward_name") String fromWardName,
        @JsonProperty("from_address") String fromAddress,
        @JsonProperty("is_new_to_address") boolean newToAddress,
        @JsonProperty("to_province_name") String toProvinceName,
        @JsonProperty("to_ward_name") String toWardName,
        @JsonProperty("to_address") String toAddress,
        @JsonProperty("to_name") String toName,
        @JsonProperty("to_phone") String toPhone,
        @JsonProperty("payment_type_id") int paymentTypeId,
        @JsonProperty("required_note") String requiredNote,
        @JsonProperty("service_type_id") int serviceTypeId,
        int weight, int length, int width, int height,
        @JsonProperty("cod_amount") int codAmount,
        String content,
        String note,
        List<GhnParcelItemRequest> items) {

    GhnCreateOrderBody withAreaAddress() {
        return toBuilder().toAddress(toWardName + ", " + toProvinceName)
                .note(GhnAdapter.ADDRESS_FALLBACK_NOTE).build();
    }
}
