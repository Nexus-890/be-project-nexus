package com.group5.paymentservice.payment;

import com.group5.paymentservice.payment.dto.request.SepayWebhookRequest;
import com.group5.paymentservice.payment.entity.PaymentSession;
import com.group5.paymentservice.payment.repository.PaymentSessionRepository;
import com.group5.paymentservice.payment.service.PaymentMatcher;
import com.group5.paymentservice.shared.config.SepayProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PaymentMatcherTest {
    private final PaymentSessionRepository sessions = mock(PaymentSessionRepository.class);
    private final PaymentMatcher matcher = matcher("SEVQR");

    @ParameterizedTest
    @ValueSource(strings = {" nx20261105001 ", "n x 20261105001", "\tNX20261105001\n", "nx2026\u00a01105001"})
    void normalizesWhitespaceAndCaseBeforeExactLookup(String memo) {
        PaymentSession session = new PaymentSession();
        when(sessions.findByMemoCode("NX20261105001")).thenReturn(Optional.of(session));
        assertThat(matcher.match(memo)).containsSame(session);
        verify(sessions).findByMemoCode("NX20261105001");
        verifyNoMoreInteractions(sessions);
    }

    @Test
    void supportsBase36MemosAndUsesCodeEvenWhenContentNamesAnotherSession() {
        PaymentSession session = new PaymentSession();
        when(sessions.findByMemoCode("NXABC123XYZ")).thenReturn(Optional.of(session));
        assertThat(matcher.match(request(" nxabc123xyz ", "SEVQR NXOTHER"))).containsSame(session);
        verify(sessions).findByMemoCode("NXABC123XYZ");
        verifyNoMoreInteractions(sessions);
    }

    @Test
    void unmatchedExplicitCodeDoesNotFallBackToDifferentContent() {
        assertThat(matcher.match(request("NXUNKNOWN", "SEVQR NXKNOWN"))).isEmpty();
        verify(sessions).findByMemoCode("NXUNKNOWN");
        verifyNoMoreInteractions(sessions);
    }

    @Test
    void usesContentAndOnlyStripsConfiguredLeadingPrefixAfterExactMiss() {
        PaymentSession session = new PaymentSession();
        when(sessions.findByMemoCode("NXABC123XYZ")).thenReturn(Optional.of(session));
        assertThat(matcher.match(request(" ", " sevqr nxabc123xyz "))).containsSame(session);
        var order = inOrder(sessions);
        order.verify(sessions).findByMemoCode("SEVQRNXABC123XYZ");
        order.verify(sessions).findByMemoCode("NXABC123XYZ");
        verifyNoMoreInteractions(sessions);
    }

    @Test
    void neverRemovesPrefixFromAnAlreadyMatchingMemo() {
        PaymentSession session = new PaymentSession();
        when(sessions.findByMemoCode("NX20261105001")).thenReturn(Optional.of(session));
        assertThat(matcher("NX").match(request(null, " nx20261105001 "))).containsSame(session);
        verify(sessions).findByMemoCode("NX20261105001");
        verifyNoMoreInteractions(sessions);
    }

    @Test
    void doesNotSearchForMemoInsideArbitraryBankTextOrStripUnconfiguredPrefix() {
        assertThat(matcher.match(request(null, "bank NXABC123XYZ done"))).isEmpty();
        assertThat(matcher("").match(request(null, "SEVQR NXABC123XYZ"))).isEmpty();
        verify(sessions).findByMemoCode("BANKNXABC123XYZDONE");
        verify(sessions).findByMemoCode("SEVQRNXABC123XYZ");
        verifyNoMoreInteractions(sessions);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\r\n\t", "NX-ABC", "NX'OR1=1", "NXABCDEFGHIJKLMNOPQRSTUVWXYZ123456789"})
    void emptyAndInvalidMemosNeverQueryTheDatabase(String memo) {
        assertThat(matcher.match(memo)).isEmpty();
        verifyNoInteractions(sessions);
    }

    private PaymentMatcher matcher(String prefix) {
        return new PaymentMatcher(sessions, new SepayProperties("970415", "0000000002", "NEXUS TEST", "compact", prefix));
    }

    private static SepayWebhookRequest request(String code, String content) {
        return new SepayWebhookRequest(1L, null, null, null, code, content, null, null, null, null, null, null);
    }
}
