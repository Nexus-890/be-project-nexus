package com.group5.notificationservice.eventlog.kafka.smoke;

import java.util.ArrayDeque;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("kafka-smoke")
public class KafkaSmokeMessageStore {

    static final int MAX_MESSAGES = 100;

    private final ArrayDeque<KafkaSmokeMessage> messages = new ArrayDeque<>(MAX_MESSAGES);

    public synchronized void add(KafkaSmokeMessage message) {
        if (messages.size() == MAX_MESSAGES) {
            messages.removeFirst();
        }
        messages.addLast(message);
    }

    public synchronized List<KafkaSmokeMessage> snapshot() {
        return List.copyOf(messages);
    }
}
