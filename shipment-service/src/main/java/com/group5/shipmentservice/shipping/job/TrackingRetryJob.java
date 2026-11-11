package com.group5.shipmentservice.shipping.job;

import com.group5.shipmentservice.shipping.service.ShipmentCreationService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TrackingRetryJob {
    private final ShipmentCreationService shipments;
    public TrackingRetryJob(ShipmentCreationService shipments) { this.shipments = shipments; }
    @Scheduled(fixedDelayString = "${nexus.shipping.tracking-retry-poll-ms:5000}")
    public void retryTracking() { shipments.retryDueTracking(); }
}
