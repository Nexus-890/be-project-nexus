package com.group5.common_web.architecture;

import com.group5.common_core.model.ApiResponse;
import com.group5.common_core.model.ProtocolResponse;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiContractRulesTest {
    @Test
    void permitsEnvelopesEmptyResponsesAndDocumentedExternalProtocols() {
        assertThatCode(() -> ApiContractRules.CONTROLLERS_RETURN_API_RESPONSE.check(
                new ClassFileImporter().importClasses(CompliantController.class)))
                .doesNotThrowAnyException();
    }

    @Test
    void bareListFailsTheBuild() {
        assertThatThrownBy(() -> ApiContractRules.CONTROLLERS_RETURN_API_RESPONSE.check(
                new ClassFileImporter().importClasses(BareListController.class)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("bareList")
                .hasMessageContaining("shared success envelope");
    }

    @Test
    void responseEntityCannotHideABareList() {
        assertThatThrownBy(() -> ApiContractRules.CONTROLLERS_RETURN_API_RESPONSE.check(
                new ClassFileImporter().importClasses(WrappedListController.class)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("wrappedList");
    }

    @Test
    void protocolExemptionRequiresAnExplanation() {
        assertThatThrownBy(() -> ApiContractRules.CONTROLLERS_RETURN_API_RESPONSE.check(
                new ClassFileImporter().importClasses(UnexplainedProtocolController.class)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("document the external protocol exemption");
    }

    @RestController
    static class CompliantController {
        @GetMapping("/single")
        ApiResponse<String> single() { return ApiResponse.of("one"); }

        @GetMapping("/list")
        ResponseEntity<ApiResponse<List<String>>> list() {
            return ResponseEntity.ok(ApiResponse.list(List.of()));
        }

        @GetMapping("/empty")
        void empty() { }

        @GetMapping("/no-content")
        ResponseEntity<Void> noContent() { return ResponseEntity.noContent().build(); }

        @GetMapping("/.well-known/jwks.json")
        @ProtocolResponse("RFC 7517 requires a JWK Set object")
        Map<String, Object> protocol() { return Map.of("keys", List.of()); }

        List<String> unmappedHelper() { return List.of(); }
    }

    @RestController
    static class BareListController {
        @GetMapping("/list")
        List<String> bareList() { return List.of(); }
    }

    @RestController
    static class WrappedListController {
        @GetMapping("/list")
        ResponseEntity<List<String>> wrappedList() { return ResponseEntity.ok(List.of()); }
    }

    @RestController
    static class UnexplainedProtocolController {
        @GetMapping("/list")
        @ProtocolResponse(" ")
        List<String> unexplained() { return List.of(); }
    }
}
