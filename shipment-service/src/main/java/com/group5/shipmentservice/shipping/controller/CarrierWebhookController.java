package com.group5.shipmentservice.shipping.controller;

import com.group5.shipmentservice.shipping.service.ShipmentStatusService;
import com.group5.shipmentservice.shipping.dto.response.CarrierWebhookResponse;

import com.group5.common_core.model.ProtocolResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/webhooks/carriers")
public class CarrierWebhookController {
    private final ShipmentStatusService statuses;

    public CarrierWebhookController(ShipmentStatusService statuses) { this.statuses = statuses; }

    @PostMapping(value = "/{carrier}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ProtocolResponse("Carrier callbacks require an unwrapped acknowledgement")
    public CarrierWebhookResponse receive(@PathVariable String carrier,
            @RequestHeader(value = "X-Carrier-Signature", required = false) String signature,
            @RequestBody byte[] body) {
        statuses.webhook(carrier, body, signature);
        return new CarrierWebhookResponse(200, "Success");
    }
}
