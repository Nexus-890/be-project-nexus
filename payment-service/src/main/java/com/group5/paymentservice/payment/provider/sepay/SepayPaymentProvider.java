package com.group5.paymentservice.payment.provider.sepay;

import com.group5.paymentservice.payment.provider.PaymentProvider;
import com.group5.paymentservice.payment.PaymentProviderNames;
import com.group5.paymentservice.payment.dto.PaymentInitiationRequest;
import com.group5.paymentservice.payment.dto.PaymentInitiationResult;
import org.springframework.stereotype.Component;

@Component
public class SepayPaymentProvider implements PaymentProvider {
    @Override
    public String providerName() {
        return PaymentProviderNames.SEPAY;
    }

    @Override
    public PaymentInitiationResult initiate(PaymentInitiationRequest request) {
        throw new UnsupportedOperationException("SEPAY VietQR initiation is not implemented yet (issue #121)");
    }
}