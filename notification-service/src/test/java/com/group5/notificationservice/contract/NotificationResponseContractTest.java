package com.group5.notificationservice.contract;

import com.group5.notificationservice.eventlog.kafka.demo.KafkaDemoMessage;
import com.group5.notificationservice.eventlog.kafka.demo.KafkaDemoMessageController;
import com.group5.notificationservice.eventlog.kafka.demo.KafkaDemoMessageRepository;
import com.group5.notificationservice.eventlog.kafka.smoke.KafkaSmokeMessage;
import com.group5.notificationservice.eventlog.kafka.smoke.KafkaSmokeMessageController;
import com.group5.notificationservice.eventlog.kafka.smoke.KafkaSmokeMessageStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class NotificationResponseContractTest {
    @Test
    void demoRecordsUseEnvelopeAndWholeListPageMetadata() throws Exception {
        UUID eventId = UUID.randomUUID();
        var repository = mock(KafkaDemoMessageRepository.class);
        when(repository.findLatest()).thenReturn(List.of(new KafkaDemoMessage(eventId, "key", "demo", 0, 1,
                Instant.parse("2026-10-23T00:00:00Z"), "{}")));
        var mvc = standaloneSetup(new KafkaDemoMessageController(repository)).build();

        mvc.perform(get("/internal/kafka-demo/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].eventId").value(eventId.toString()))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(1))
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.page.totalPages").value(1));
    }

    @Test
    void smokeRecordsAndEmptyListsUseEnvelopeAndPageMetadata() throws Exception {
        var store = new KafkaSmokeMessageStore();
        var mvc = standaloneSetup(new KafkaSmokeMessageController(store)).build();
        mvc.perform(get("/internal/kafka-smoke/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.page.size").value(1))
                .andExpect(jsonPath("$.page.totalElements").value(0))
                .andExpect(jsonPath("$.page.totalPages").value(0));

        store.add(new KafkaSmokeMessage("event-id", "key", "smoke", 0, 1,
                Instant.parse("2026-10-23T00:00:00Z"), "{}"));
        mvc.perform(get("/internal/kafka-smoke/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].eventId").value("event-id"))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }
}
