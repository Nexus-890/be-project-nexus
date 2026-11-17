package com.group5.ratingservice.shared;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

class TestTimezoneTest {
    @Test
    void forkedTestJvmUsesUtcForPostgresConnections() {
        assertThat(System.getProperty("user.timezone")).isEqualTo("UTC");
        assertThat(ZoneId.systemDefault()).isEqualTo(ZoneId.of("UTC"));
        assertThat(TimeZone.getDefault().getID()).isEqualTo("UTC");
    }
}
