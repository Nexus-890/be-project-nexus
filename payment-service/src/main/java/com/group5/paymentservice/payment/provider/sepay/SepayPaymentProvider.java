package com.group5.paymentservice.payment.provider.sepay;

import com.group5.paymentservice.payment.provider.PaymentProvider;
import com.group5.paymentservice.payment.provider.PaymentProviderNames;
import com.group5.paymentservice.payment.provider.dto.PaymentInitiationRequest;
import com.group5.paymentservice.payment.provider.dto.PaymentInitiationResult;
import com.group5.common_core.exception.DomainException;
import com.group5.paymentservice.shared.config.SepayProperties;
import com.group5.paymentservice.shared.exception.PaymentErrorCode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Component
public class SepayPaymentProvider implements PaymentProvider {
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999999");
    private final SepayProperties properties;

    public SepayPaymentProvider(SepayProperties properties) {
        this.properties = properties;
    }

    @Override
    public String providerName() {
        return PaymentProviderNames.SEPAY;
    }

    @Override
    public PaymentInitiationResult initiate(PaymentInitiationRequest request) {
        if (request == null || request.sessionId() == null || request.expiresAt() == null
                || !"VND".equals(request.currency()) || request.amount() == null
                || request.amount().signum() <= 0 || request.amount().compareTo(MAX_AMOUNT) > 0
                || request.amount().stripTrailingZeros().scale() > 0
                || request.memoCode() == null || !request.memoCode().matches("[A-Z0-9]{1,32}")) {
            throw new DomainException(PaymentErrorCode.INVALID_VIETQR_DATA);
        }
        String prefix = properties.transferContentPrefix();
        String content = prefix == null || prefix.isBlank()
                ? request.memoCode() : prefix + " " + request.memoCode();
        String url = "https://vietqr.app/img?acc=" + encode(properties.bankAccount())
                + "&bank=" + encode(properties.bankId())
                + "&amount=" + request.amount().toBigIntegerExact()
                + "&des=" + encode(content)
                + "&template=" + encode(properties.vietqrTemplate())
                + "&showinfo=true&holder=" + encode(properties.accountName());
        return new PaymentInitiationResult(url, properties.bankAccount());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}