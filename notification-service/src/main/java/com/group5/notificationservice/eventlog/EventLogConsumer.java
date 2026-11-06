package com.group5.notificationservice.eventlog;

import com.group5.common_events.Topics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class EventLogConsumer {
    private final EventLogRecorder recorder;

    public EventLogConsumer(EventLogRecorder recorder) { this.recorder = recorder; }

    @KafkaListener(topics = {Topics.USER, Topics.CATALOG, Topics.COMMERCE, Topics.PAYMENT,
            Topics.INVENTORY, Topics.SHIPMENT, Topics.RATING, Topics.AUCTION},
            groupId = "${nexus.event-log.group-id:nexus.notification.event-log.v1}",
            containerFactory = "eventLogListenerContainerFactory")
    public void receive(ConsumerRecord<String, String> record) {
        recorder.recordRaw(record.value(), record.topic());
    }
}
