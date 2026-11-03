package com.group5.paymentservice.payment.provider;

import com.group5.paymentservice.payment.provider.dto.PaymentInitiationRequest;
import com.group5.paymentservice.payment.provider.dto.PaymentInitiationResult;

public interface PaymentProvider {
    String providerName();

    PaymentInitiationResult initiate(PaymentInitiationRequest request);
}