package com.group5.common_web.exception;

import com.group5.common_core.constant.ErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.common_core.model.ApiResponse;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiProblemContractTest {
    private MockMvc mvc;
    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new Fixture())
                .setControllerAdvice(new GlobalExceptionHandler(null)).build();
    }

    @Test void successHasDataAndAllListsHavePageIncludingEmpty() throws Exception {
        mvc.perform(get("/single")).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("one"))
                .andExpect(jsonPath("$.page").doesNotExist()).andExpect(jsonPath("$.result").doesNotExist());
        mvc.perform(get("/list")).andExpect(jsonPath("$.data").isArray()).andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(1)).andExpect(jsonPath("$.page.totalElements").value(0))
                .andExpect(jsonPath("$.page.totalPages").value(0));
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
    @RestController static class Fixture {
        @GetMapping("/single") ApiResponse<?> single() { return ApiResponse.of(Map.of("id", "one")); }
        @GetMapping("/list") ApiResponse<?> list() { return ApiResponse.list(List.of()); }
        @PostMapping("/validate") ApiResponse<?> validate(@Valid @RequestBody Input input) { return ApiResponse.of(input); }
        @GetMapping("/uuid/{id}") ApiResponse<?> uuid(@PathVariable UUID id) { return ApiResponse.of(id); }
        @GetMapping("/unexpected") ApiResponse<?> unexpected() { throw new IllegalStateException("secret-database-detail"); }
        @GetMapping("/domain") ApiResponse<?> domain() {
            throw new DomainException(new ErrorCode() {
                public String code() { return "TEST_DUPLICATE"; }
                public HttpStatus status() { return HttpStatus.CONFLICT; }
                public String message() { return "Already exists"; }
            });
        }
    }
}
