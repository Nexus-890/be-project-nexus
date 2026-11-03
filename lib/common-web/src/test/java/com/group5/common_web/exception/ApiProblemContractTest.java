package com.group5.common_web.exception;

import com.group5.common_core.constant.ErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.common_core.model.ApiResponse;
import com.group5.common_core.model.PageInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiProblemContractTest {
    private static final UUID SKU = UUID.fromString("c8000000-0000-4000-8000-000000000003");
    private static final ErrorCode PRICE_CHANGED = new ErrorCode() {
        public String code() { return "TEST_PRICE_CHANGED"; }
        public HttpStatus status() { return HttpStatus.CONFLICT; }
        public String message() { return "Price changed"; }
    };
    private MockMvc mvc;
    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new Fixture())
                .setControllerAdvice(new GlobalExceptionHandler(null)).build();
    }

    @Test void successHasDataAndAllListsHavePageIncludingEmpty() throws Exception {
        mvc.perform(get("/single")).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("one"))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.message").value("response success"))
                .andExpect(jsonPath("$.page").doesNotExist()).andExpect(jsonPath("$.result").doesNotExist());
        mvc.perform(get("/list")).andExpect(jsonPath("$.data").isArray()).andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.message").value("response success"))
                .andExpect(jsonPath("$.page.size").value(1)).andExpect(jsonPath("$.page.totalElements").value(0))
                .andExpect(jsonPath("$.page.totalPages").value(0));
    }

    @Test void successMetadataDefaultsForOmittedOrNullValuesAndPreservesExplicitValues() {
        var mapper = JsonMapper.builder().build();
        for (String json : List.of("{\"data\":null}", "{\"code\":null,\"message\":null,\"data\":null}")) {
            var response = mapper.readValue(json, ApiResponse.class);
            var body = mapper.readTree(mapper.writeValueAsString(response));
            assertThat(body.get("code").intValue()).isZero();
            assertThat(body.get("message").stringValue()).isEqualTo("response success");
            assertThat(body.has("data")).isTrue();
            assertThat(body.get("data").isNull()).isTrue();
            assertThat(body.has("page")).isFalse();
        }
        var explicit = mapper.readValue("{\"code\":7,\"message\":\"Created\",\"data\":\"one\"}", ApiResponse.class);
        assertThat(explicit.code()).isEqualTo(7);
        assertThat(explicit.message()).isEqualTo("Created");

        var page = new PageInfo(0, 1, 0, 0);
        for (var response : List.of(ApiResponse.of("one", "Created"), ApiResponse.list(List.of(), "Created"),
                ApiResponse.page(List.of(), page, "Created"))) {
            var body = mapper.readTree(mapper.writeValueAsString(response));
            assertThat(body.get("code").intValue()).isZero();
            assertThat(body.get("message").stringValue()).isEqualTo("Created");
        }
    }

    @Test void validationReportsEveryFailedFieldWithCodesAndDetails() throws Exception {
        mvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"email\":\"invalid\",\"quantity\":0}"))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.instance").value("/validate"))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("name", "email", "quantity")))
                .andExpect(jsonPath("$.errors[*].code", containsInAnyOrder("VALIDATION_NOT_BLANK", "VALIDATION_EMAIL", "VALIDATION_POSITIVE")))
                .andExpect(jsonPath("$.errors[0].detail").isNotEmpty())
                .andExpect(jsonPath("$.errors[0].message").doesNotExist());
    }

    @Test void businessCodeRemainsDistinctFromHttpStatus() throws Exception {
        mvc.perform(get("/domain")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TEST_DUPLICATE"))
                .andExpect(jsonPath("$.detail").value("Already exists"));
    }

    @Test void domainExtensionsAreTopLevelProblemFieldsAndPreserveDecimalPrices() throws Exception {
        mvc.perform(get("/price-changed")).andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("TEST_PRICE_CHANGED"))
                .andExpect(jsonPath("$.skuId").value(SKU.toString()))
                .andExpect(jsonPath("$.oldPrice").value(150000.5))
                .andExpect(jsonPath("$.newPrice").value(180000.75))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.properties").doesNotExist());
    }

    @Test void domainExtensionsCannotReplaceProblemMetadata() throws Exception {
        mvc.perform(get("/domain-reserved")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem:domain-error"))
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("Price changed"))
                .andExpect(jsonPath("$.instance").value("/domain-reserved"))
                .andExpect(jsonPath("$.code").value("TEST_PRICE_CHANGED"))
                .andExpect(jsonPath("$.traceId").doesNotExist())
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.skuId").value(SKU.toString()));
    }

    @Test void domainExtensionPropertiesAreCopiedAndImmutableAndLegacyConstructorsRemainEmpty() {
        Map<String, Object> properties = new LinkedHashMap<>(priceChangeProperties());
        DomainException error = new DomainException(PRICE_CHANGED, PRICE_CHANGED.message(), properties);
        properties.clear();

        assertThat(error.getProperties()).containsAllEntriesOf(priceChangeProperties());
        assertThatThrownBy(() -> error.getProperties().put("newPrice", BigDecimal.ZERO))
                .isInstanceOf(UnsupportedOperationException.class);
        for (DomainException legacy : List.of(new DomainException(PRICE_CHANGED),
                new DomainException(PRICE_CHANGED, "Custom detail"), new DomainException(409, "Conflict"),
                new DomainException("Unexpected error"))) {
            assertThat(legacy.getProperties()).isEmpty();
        }
    }

    @Test void frameworkFailuresRemainProblemJsonAndInternalDetailsAreHidden() throws Exception {
        mvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"));
        mvc.perform(get("/uuid/not-a-uuid")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        mvc.perform(post("/single")).andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("GET")))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        mvc.perform(post("/validate").contentType(MediaType.TEXT_PLAIN).content("test"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        mvc.perform(get("/single").accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable()).andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"));
        mvc.perform(get("/unexpected")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.traceId").doesNotExist());
    }

    record Input(@NotBlank String name, @Email String email, @Positive int quantity) { }
    private static Map<String, Object> priceChangeProperties() {
        return Map.of("skuId", SKU, "oldPrice", new BigDecimal("150000.50"), "newPrice", new BigDecimal("180000.75"));
    }
    @RestController static class Fixture {
        @GetMapping("/single") ApiResponse<?> single() { return ApiResponse.of(Map.of("id", "one")); }
        @GetMapping("/list") ApiResponse<?> list() { return ApiResponse.list(List.of()); }
        @PostMapping("/validate") ApiResponse<?> validate(@Valid @RequestBody Input input) { return ApiResponse.of(input); }
        @GetMapping("/uuid/{id}") ApiResponse<?> uuid(@PathVariable UUID id) { return ApiResponse.of(id); }
        @GetMapping("/unexpected") ApiResponse<?> unexpected() { throw new IllegalStateException("secret-database-detail"); }
        @GetMapping("/price-changed") ApiResponse<?> priceChanged() {
            throw new DomainException(PRICE_CHANGED, PRICE_CHANGED.message(), priceChangeProperties());
        }
        @GetMapping("/domain-reserved") ApiResponse<?> reservedDomainProperties() {
            Map<String, Object> properties = new LinkedHashMap<>(priceChangeProperties());
            properties.putAll(Map.of("type", "urn:problem:fake", "title", "Fake title", "status", 200,
                    "detail", "Fake detail", "instance", "/fake", "code", "FAKE_CODE", "traceId", "fake-trace",
                    "errors", List.of("fake-error")));
            throw new DomainException(PRICE_CHANGED, PRICE_CHANGED.message(), properties);
        }
        @GetMapping("/domain") ApiResponse<?> domain() {
            throw new DomainException(new ErrorCode() {
                public String code() { return "TEST_DUPLICATE"; }
                public HttpStatus status() { return HttpStatus.CONFLICT; }
                public String message() { return "Already exists"; }
            });
        }
    }
}
