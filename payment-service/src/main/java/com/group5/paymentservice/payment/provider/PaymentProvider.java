package com.group5.paymentservice.payment.provider;

import com.group5.paymentservice.payment.dto.PaymentInitiationRequest;
import com.group5.paymentservice.payment.dto.PaymentInitiationResult;

public interface PaymentProvider {
    String providerName();

    PaymentInitiationResult initiate(PaymentInitiationRequest request);
}