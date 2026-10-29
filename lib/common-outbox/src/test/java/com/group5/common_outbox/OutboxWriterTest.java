package com.group5.common_outbox;

import com.group5.common_events.EventEnvelope;
import com.group5.common_events.Topics;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OutboxWriterTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final JsonMapper mapper = JsonMapper.shared();
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final EventEnvelope<?> event = new EventEnvelope<>(UUID.randomUUID(), "ProductCreated", "product-1",
            Instant.parse("2026-10-20T08:00:00Z"), "4bf92f3577b34da6a3ce929d0e0e4736", Map.of("name", "Phone"));

    @Test
    void rejectsCallsOutsideATransactionEvenWithoutASpringProxy() {
        DataSource dataSource = mock(DataSource.class);
        OutboxWriter writer = new OutboxWriter(jdbc, mapper, new DataSourceTransactionManager(dataSource));

        assertThatThrownBy(() -> writer.write("Product", Topics.CATALOG, event))
                .isInstanceOf(IllegalTransactionStateException.class);

        verifyNoInteractions(jdbc, dataSource);
    }

    @Test
    void joinsTheExistingTransactionAndPersistsTheCompleteEnvelope() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        OutboxWriter writer = new OutboxWriter(jdbc, mapper, transactions);

        assertThat(writer.write("Product", Topics.CATALOG, event)).isEqualTo(event.eventId());

        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactions).getTransaction(definition.capture());
        assertThat(definition.getValue().getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_MANDATORY);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(anyString(), eq(event.eventId()), eq("Product"), eq(event.aggregateId()),
                eq(event.eventType()), eq(1), eq(Topics.CATALOG), payload.capture(), eq(event.traceId()),
                eq(Timestamp.from(event.occurredAt())));
        assertThat(mapper.readTree(payload.getValue())).isEqualTo(mapper.valueToTree(event));
    }

    @Test
    void allowsAnExplicitSchemaVersionWithoutChangingTheEnvelope() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        OutboxWriter writer = new OutboxWriter(jdbc, mapper, transactions);

        writer.write("Product", 2, Topics.CATALOG, event);

        verify(jdbc).update(anyString(), eq(event.eventId()), eq("Product"), eq(event.aggregateId()),
                eq(event.eventType()), eq(2), eq(Topics.CATALOG), eq(mapper.writeValueAsString(event)),
                eq(event.traceId()), any(Timestamp.class));
    }

    @Test
    void rejectsInvalidRowMetadataBeforeInserting() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        OutboxWriter writer = new OutboxWriter(jdbc, mapper, transactions);

        assertThatThrownBy(() -> writer.write(" ", Topics.CATALOG, event)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> writer.write("Product", "x".repeat(81), event)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> writer.write("Product", 0, Topics.CATALOG, event)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }
}
