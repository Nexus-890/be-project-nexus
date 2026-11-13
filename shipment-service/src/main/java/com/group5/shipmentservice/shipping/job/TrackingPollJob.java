package com.group5.shipmentservice.shipping.job;

import com.group5.shipmentservice.shipping.service.ShipmentStatusService;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "nexus.shipping.status-poll-enabled", havingValue = "true", matchIfMissing = true)
public class TrackingPollJob {
    private final ShipmentStatusService shipments;

    public TrackingPollJob(ShipmentStatusService shipments) { this.shipments = shipments; }

    @Scheduled(fixedDelayString = "${nexus.shipping.status-poll-job-ms:5000}",
            initialDelayString = "${nexus.shipping.status-poll-initial-delay-ms:60000}")
    public void poll() { shipments.poll(); }
}
