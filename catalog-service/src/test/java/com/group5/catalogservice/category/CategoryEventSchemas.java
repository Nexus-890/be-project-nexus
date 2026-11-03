package com.group5.catalogservice.category;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Loads the actual payload contracts; no second copy of the schemas lives in tests. */
final class CategoryEventSchemas {
    private static final Map<String, Schema> SCHEMAS = load();

    static void assertValid(JsonNode envelope) {
        Schema schema = SCHEMAS.get(envelope.path("eventType").asString());
        assertThat(schema).as("schema for %s", envelope.path("eventType")).isNotNull();
        assertThat(schema.validate(envelope.path("payload").toString(), InputFormat.JSON,
                context -> context.executionConfig(config -> config.formatAssertionsEnabled(true))))
                .as("payload schema errors for %s", envelope.path("eventType")).isEmpty();
        assertThat(envelope.path("aggregateId").asString())
                .isEqualTo(envelope.path("payload").path("categoryId").asString());
    }

    private static Map<String, Schema> load() {
        try {
            String contract = Files.readString(Path.of("../docs/api/events.md"));
            SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7);
            Map<String, Schema> schemas = new HashMap<>();
            for (String event : new String[]{"CategoryCreated", "CategoryUpdated", "CategoryDeleted"}) {
                var section = Pattern.compile("(?ms)^### " + event + "\\s*\\n(.*?)(?=^### |\\z)")
                        .matcher(contract);
                if (!section.find()) {
                    throw new IllegalStateException("Missing event contract: " + event);
                }
                var schema = Pattern.compile("(?s)```json\\s*\\n(.*?)```").matcher(section.group(1));
                if (!schema.find()) {
                    throw new IllegalStateException("Missing JSON schema: " + event);
                }
                schemas.put(event, registry.getSchema(schema.group(1)));
            }
            return Map.copyOf(schemas);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read docs/api/events.md", exception);
        }
    }

    private CategoryEventSchemas() {
    }
}
