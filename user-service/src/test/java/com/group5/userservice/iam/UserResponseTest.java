package com.group5.userservice.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.group5.userservice.iam.dto.UserResponse;
import com.group5.userservice.iam.entity.Role;
import com.group5.userservice.iam.entity.User;
import com.group5.userservice.iam.enums.Gender;
import com.group5.userservice.iam.enums.UserStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UserResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void fromMapsTheAssignedRoleCodes() {
        UserResponse response = UserResponse.from(userWithRoles("SELLER", "BUYER"));

        assertThat(response.roles()).containsExactly("BUYER", "SELLER");
    }

    @Test
    void serializedResponseDoesNotContainPasswordHash() throws Exception {
        JsonNode json = objectMapper.valueToTree(UserResponse.from(userWithRoles("BUYER")));

        assertThat(json.has("passwordHash")).isFalse();
    }

    @Test
    void serializedResponseDoesNotContainIdentityNumber() throws Exception {
        JsonNode json = objectMapper.valueToTree(UserResponse.from(userWithRoles("BUYER")));

        assertThat(json.has("identityNumber")).isFalse();
    }

    private User userWithRoles(String... codes) {
        User user = new User();
        user.setId(UUID.fromString("7c47f673-6a1f-4b7b-bfb6-90e5b674eb81"));
        user.setEmail("buyer@nexus.local");
        user.setPhone("0901234567");
        user.setPasswordHash("$2a$12$hash");
        user.setFullName("Buyer One");
        user.setIdentityNumber("ID123456");
        user.setGender(Gender.MALE);
        user.setAddress("1 Main Street");
        user.setDateOfBirth(LocalDate.parse("2000-01-01"));
        user.setStatus(UserStatus.ACTIVE);
        user.setCreatedAt(OffsetDateTime.parse("2026-01-02T03:04:05Z"));
        for (String code : codes) {
            Role role = new Role();
            role.setCode(code);
            user.getRoles().add(role);
        }
        return user;
    }
}
