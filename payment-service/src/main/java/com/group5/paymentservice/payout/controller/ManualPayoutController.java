package com.group5.paymentservice.payout.controller;

import static com.group5.paymentservice.shared.exception.PaymentErrorCode.MANUAL_PAYOUT_CONFLICT;

import com.group5.common_core.exception.DomainException;
import com.group5.common_core.model.ApiResponse;
import com.group5.common_core.model.ProtocolResponse;
import com.group5.common_security.CurrentUser;
import com.group5.paymentservice.payout.dto.request.*;
import com.group5.paymentservice.payout.dto.request.ManualPayoutCommands.*;
import com.group5.paymentservice.payout.dto.response.*;
import com.group5.paymentservice.payout.service.*;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@RestController
@RequestMapping("/api/v1/payments/manual-payouts")
@PreAuthorize("hasAnyRole('ADMIN','SUPPORT_STAFF')")
@RequiredArgsConstructor
public class ManualPayoutController {
    private final ManualPayoutQueryService queries;
    private final ManualPayoutService commands;

    @GetMapping("/capabilities")
    public ApiResponse<ManualPayoutCapabilities> capabilities(CurrentUser user) {
        return ApiResponse.of(queries.capabilities(user));
    }

    @GetMapping
    public ApiResponse<List<ManualPayoutView>> queue(
            CurrentUser user, @Valid @ModelAttribute ManualPayoutFilter filter) {
        var page = queries.queue(user, filter);
        return ApiResponse.page(page.content(), page.page());
    }

    @GetMapping("/{payoutId}")
    public ApiResponse<ManualPayoutView> detail(CurrentUser user, @PathVariable UUID payoutId) {
        return ApiResponse.of(queries.detail(user, payoutId));
    }

    @PostMapping("/{payoutId}/readiness-check")
    public ResponseEntity<ApiResponse<ManualPayoutView>> readiness(
            CurrentUser user,
            @PathVariable UUID payoutId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody PayoutVersionCommand body) {
        return response(commands.execute(user, payoutId, null, null, "READINESS_CHECK", key, body));
    }

    @PostMapping("/{payoutId}/claims")
    public ResponseEntity<ApiResponse<ManualPayoutView>> claim(
            CurrentUser user,
            @PathVariable UUID payoutId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody ClaimCommand body) {
        return response(commands.execute(user, payoutId, null, null, "CLAIM", key, body));
    }

    @PostMapping("/{payoutId}/attempts/{attemptId}/qr-issuances")
    public ResponseEntity<ApiResponse<ManualPayoutView>> issue(
            CurrentUser user,
            @PathVariable UUID payoutId,
            @PathVariable UUID attemptId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody AttemptVersionCommand body) {
        return response(commands.execute(user, payoutId, attemptId, null, "ISSUE_QR", key, body));
    }

    @GetMapping(value = "/{payoutId}/attempts/{attemptId}/qr", produces = MediaType.IMAGE_PNG_VALUE)
    @ProtocolResponse("Authenticated private bank QR is an image rather than a JSON envelope")
    public ResponseEntity<byte[]> qr(
            CurrentUser user, @PathVariable UUID payoutId, @PathVariable UUID attemptId) {
        return binary(
                commands.qr(user, payoutId, attemptId),
                "image/png",
                "inline; filename=\"payout-qr.png\"");
    }

    @PostMapping("/{payoutId}/attempts/{attemptId}/release")
    public ResponseEntity<ApiResponse<ManualPayoutView>> release(
            CurrentUser user,
            @PathVariable UUID payoutId,
            @PathVariable UUID attemptId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody AttemptVersionCommand body) {
        return response(commands.execute(user, payoutId, attemptId, null, "RELEASE", key, body));
    }

    @PostMapping(
            value = "/{payoutId}/attempts/{attemptId}/evidence",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ManualPayoutView>> upload(
            CurrentUser user,
            @PathVariable UUID payoutId,
            @PathVariable UUID attemptId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestParam Long expectedAttemptVersion,
            @RequestPart("file") MultipartFile file) {
        return response(
                commands.upload(user, payoutId, attemptId, key, expectedAttemptVersion, file));
    }

    @GetMapping("/{payoutId}/attempts/{attemptId}/evidence/{evidenceId}")
    @ProtocolResponse("Authenticated private transfer evidence is binary")
    public ResponseEntity<byte[]> evidence(
            CurrentUser user,
            @PathVariable UUID payoutId,
            @PathVariable UUID attemptId,
            @PathVariable UUID evidenceId) {
        var evidence = commands.evidence(user, payoutId, attemptId, evidenceId);
        return binary(
                evidence.content(),
                evidence.contentType(),
                "attachment; filename=\"payout-evidence\"");
    }

    @PostMapping("/{payoutId}/attempts/{attemptId}/confirmation")
    public ResponseEntity<ApiResponse<ManualPayoutView>> confirm(
            CurrentUser user,
            @PathVariable UUID payoutId,
            @PathVariable UUID attemptId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody ConfirmationCommand body) {
        return response(
                commands.execute(user, payoutId, attemptId, null, "CONFIRM_TRANSFER", key, body));
    }

    @PostMapping("/{payoutId}/attempts/{attemptId}/problems")
    public ResponseEntity<ApiResponse<ManualPayoutView>> problems(
            CurrentUser user,
            @PathVariable UUID payoutId,
            @PathVariable UUID attemptId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody ProblemCommand body) {
        return response(
                commands.execute(user, payoutId, attemptId, null, "REPORT_PROBLEM", key, body));
    }

    @PostMapping("/{payoutId}/attempts/{attemptId}/resolution")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ManualPayoutView>> resolve(
            CurrentUser user,
            @PathVariable UUID payoutId,
            @PathVariable UUID attemptId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody ResolutionCommand body) {
        return response(
                commands.execute(user, payoutId, attemptId, body.caseId(), "RESOLVE", key, body));
    }

    @PostMapping("/{payoutId}/attempts/{attemptId}/receipt-disputes/{disputeId}/reviews")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ManualPayoutView>> review(
            CurrentUser user,
            @PathVariable UUID payoutId,
            @PathVariable UUID attemptId,
            @PathVariable UUID disputeId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody ReviewCommand body) {
        return response(
                commands.execute(
                        user, payoutId, attemptId, disputeId, "REVIEW_DISPUTE", key, body));
    }

    @PostMapping("/{payoutId}/retries")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ManualPayoutView>> retry(
            CurrentUser user,
            @PathVariable UUID payoutId,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody PayoutVersionCommand body) {
        return response(commands.execute(user, payoutId, null, null, "RETRY", key, body));
    }

    private ResponseEntity<ApiResponse<ManualPayoutView>> response(ManualCommandResult result) {
        if (result.conflict())
            throw new DomainException(
                    MANUAL_PAYOUT_CONFLICT,
                    MANUAL_PAYOUT_CONFLICT.message(),
                    Map.of(
                            "currentVersion",
                            result.result().version(),
                            "payoutStatus",
                            result.result().status(),
                            "allowedActions",
                            result.result().allowedActions()));
        return ResponseEntity.status(result.created() && !result.replay() ? 201 : 200)
                .body(ApiResponse.of(result.result()));
    }

    private ResponseEntity<byte[]> binary(byte[] bytes, String mime, String disposition) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(mime))
                .header(HttpHeaders.CACHE_CONTROL, "no-store, private")
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .header("X-Content-Type-Options", "nosniff")
                .body(bytes);
    }
}
