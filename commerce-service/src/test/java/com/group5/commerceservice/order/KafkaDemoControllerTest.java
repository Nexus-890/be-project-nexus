package com.group5.commerceservice.order;

import com.group5.commerceservice.order.dto.KafkaDemoRequest;
import com.group5.commerceservice.order.dto.KafkaDemoResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KafkaDemoControllerTest {
    private final KafkaDemoPublisher publisher = mock(KafkaDemoPublisher.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new KafkaDemoController(publisher)).build();
    }

    @Test
    void returnsOutboxAndBrokerMetadataForValidRequest() throws Exception {
        UUID outboxId = UUID.fromString("019b6f90-55cf-73f7-a321-0407473f21a4");
        when(publisher.publish(any())).thenReturn(new KafkaDemoResult(outboxId, outboxId.toString(), "order-1", "topic", 2, 7));

        mvc.perform(post("/internal/kafka-demo/messages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"order-1\",\"message\":\"hello\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outboxId").value(outboxId.toString()))
                .andExpect(jsonPath("$.eventId").value(outboxId.toString()))
                .andExpect(jsonPath("$.partition").value(2))
                .andExpect(jsonPath("$.offset").value(7));
        verify(publisher).publish(new KafkaDemoRequest("order-1", "hello"));
    }

    @Test
    void rejectsEmptyMessageAndInvalidKeyWithoutProducing() throws Exception {
        mvc.perform(post("/internal/kafka-demo/messages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"bad key\",\"message\":\"\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(publisher);
    }
}
