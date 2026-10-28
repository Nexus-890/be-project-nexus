package com.group5.common_outbox;

import com.group5.common_outbox.processed.ProcessedEvent;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;

@SpringBootApplication
@EntityScan(basePackageClasses = ProcessedEvent.class)
public class OutboxTestApplication {
}
