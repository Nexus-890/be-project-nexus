package com.group5.commerceservice.order;

import com.group5.commerceservice.order.dto.KafkaSmokeRequest;
import com.group5.commerceservice.order.dto.KafkaSmokeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KafkaSmokeControllerTest {
    private final KafkaSmokePublisher publisher = mock(KafkaSmokePublisher.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new KafkaSmokeController(publisher)).build();
    }

    @Test
    void returnsBrokerMetadataForValidRequest() throws Exception {
        when(publisher.publish(any())).thenReturn(new KafkaSmokeResult("event-1", "order-1", "topic", 2, 7));
        mvc.perform(post("/internal/kafka-smoke/messages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"order-1\",\"message\":\"hello\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value("event-1"))
                .andExpect(jsonPath("$.partition").value(2))
                .andExpect(jsonPath("$.offset").value(7));
        verify(publisher).publish(new KafkaSmokeRequest("order-1", "hello"));
    }

    @Test
    void rejectsEmptyMessageAndInvalidKeyWithoutProducing() throws Exception {
        mvc.perform(post("/internal/kafka-smoke/messages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"bad key\",\"message\":\"\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(publisher);
    }
}
