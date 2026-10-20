package com.group5.common_events;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TopicsTest {

    private static final Path TOPICS_MANIFEST = Path.of("..", "..", "infra", "local-dev", "kafka", "topics.conf");

    @Test
    void matchesTheDomainTopicsThatTheTopicScriptCreates() throws IOException {
        assertThat(constants()).containsExactlyInAnyOrderElementsOf(domainTopicsInManifest());
    }

    @Test
    void hasNoDuplicateName() {
        assertThat(constants()).doesNotHaveDuplicates();
    }

    private static List<String> constants() {
        return Arrays.stream(Topics.class.getDeclaredFields())
                .filter(field -> Modifier.isPublic(field.getModifiers())
                        && Modifier.isStatic(field.getModifiers())
                        && field.getType() == String.class)
                .map(TopicsTest::valueOf)
                .toList();
    }

    private static String valueOf(Field field) {
        try {
            return (String) field.get(null);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static List<String> domainTopicsInManifest() throws IOException {
        return Files.readAllLines(TOPICS_MANIFEST).stream()
                .filter(line -> line.startsWith("topic|"))
                .map(line -> line.split("\\|"))
                .filter(columns -> !columns[4].equals("demo"))
                .map(columns -> columns[1])
                .toList();
    }
}
