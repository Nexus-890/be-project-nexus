package com.group5.paymentservice.payment;

import com.group5.common_core.exception.DomainException;
import com.group5.paymentservice.payment.provider.dto.PaymentInitiationRequest;
import com.group5.paymentservice.payment.provider.sepay.SepayPaymentProvider;
import com.group5.paymentservice.shared.config.SepayProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SepayPaymentProviderTest {
    private final SepayPaymentProvider provider = new SepayPaymentProvider(
            new SepayProperties("970416", "0000000002", "NEXUS TEST & CO", "compact", ""));

    @Test
    void buildsDeterministicQuickLinkWithExactAmountMemoAndEncodedName() {
        var request = request("350000.00", "VND", "NX20261105001");
        var result = provider.initiate(request);
        assertThat(result.bankAccount()).isEqualTo("0000000002");
        assertThat(result.qrImageUrl()).isEqualTo(
                "https://vietqr.app/img?acc=0000000002&bank=970416"
                        + "&amount=350000&des=NX20261105001&template=compact"
                        + "&showinfo=true&holder=NEXUS%20TEST%20%26%20CO");
        assertThat(provider.initiate(request)).isEqualTo(result);
    }

    @Test
    void addsConfiguredBankPrefixWithoutChangingSessionMemoOrDeadline() {
        var vietinbank = new SepayPaymentProvider(new SepayProperties(
                "970415", "0123456789", "NEXUS TEST", "compact", "SEVQR"));
        var request = request("350000.00", "VND", "NX20261105001");
        var result = vietinbank.initiate(request);

        assertThat(result.qrImageUrl()).isEqualTo(
                "https://vietqr.app/img?acc=0123456789&bank=970415"
                        + "&amount=350000&des=SEVQR%20NX20261105001&template=compact"
                        + "&showinfo=true&holder=NEXUS%20TEST");
        assertThat(vietinbank.initiate(request)).isEqualTo(result);
        assertThat(request.memoCode()).isEqualTo("NX20261105001");
        assertThat(request.expiresAt()).isEqualTo(Instant.parse("2026-11-05T10:15:00Z"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "350000.01", "10000000000000"})
    void rejectsUnrepresentableAmountsWithoutRounding(String amount) {
        assertThatThrownBy(() -> provider.initiate(request(amount, "VND", "NX001")))
                .isInstanceOf(DomainException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "nx001", "NX 001", "NX&amount=1", "ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567"})
    void rejectsInvalidStoredMemos(String memo) {
        assertThatThrownBy(() -> provider.initiate(request("350000", "VND", memo)))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsOtherCurrenciesAndAcceptsMaximumWholeVndAmount() {
        assertThatThrownBy(() -> provider.initiate(request("350000", "USD", "NX001")))
                .isInstanceOf(DomainException.class);
        assertThat(provider.initiate(request("9999999999999.00", "VND", "NX001")).qrImageUrl())
                .contains("amount=9999999999999&");
    }

    private PaymentInitiationRequest request(String amount, String currency, String memo) {
        return new PaymentInitiationRequest(UUID.randomUUID(), new BigDecimal(amount), currency, memo,
                Instant.parse("2026-11-05T10:15:00Z"));
    }
}
