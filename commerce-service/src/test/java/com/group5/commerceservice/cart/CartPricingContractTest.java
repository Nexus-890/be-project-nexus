package com.group5.commerceservice.cart;

import com.group5.commerceservice.cart.controller.CartController;
import com.group5.commerceservice.cart.dto.response.CartIssue;
import com.group5.commerceservice.cart.dto.response.CartPricingResponse;
import com.group5.commerceservice.cart.dto.response.CartValidationResponse;
import com.group5.commerceservice.cart.service.CartPricingService;
import com.group5.commerceservice.cart.service.CartService;
import com.group5.commerceservice.checkout.controller.CheckoutController;
import com.group5.commerceservice.checkout.dto.CheckoutPreviewResponse;
import com.group5.commerceservice.shared.exception.CommerceErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.common_web.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CartPricingContractTest {
    private static final String BODY = """
            {"shippingAddress":{"recipientName":"Buyer","phoneNumber":"0901234567",
            "addressLine":"123 Street","wardCode":"1003646","provinceCode":"1000001",
            "countryCode":"VN"},"paymentMethod":"SEPAY","promoCode":"D01"}
            """;
    private final CartPricingService service = mock(CartPricingService.class);
    private MockMvc mvc;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new CartController(mock(CartService.class), service), new CheckoutController(service))
                .setControllerAdvice(new GlobalExceptionHandler(null)).build();
    }

    @Test void pricingUsesNumericMoneyAndExplicitNullShippingInsideOneEnvelope() throws Exception {
        when(service.pricing(any(), eq("D01"), isNull())).thenReturn(new CartPricingResponse(
                new BigDecimal("300000"), BigDecimal.ZERO, BigDecimal.ZERO, null, new BigDecimal("300000"), List.of()));
        mvc.perform(get("/api/v1/carts/me/pricing").param("promoCode", "D01"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("response success"))
                .andExpect(jsonPath("$.data.subtotal").value(300000))
                .andExpect(jsonPath("$.data.taxAmount").value(0))
                .andExpect(jsonPath("$.data.discountAmount").value(0))
                .andExpect(jsonPath("$.data.total").value(300000))
                .andExpect(jsonPath("$.data.estimatedShippingFee").value(nullValue()))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"estimatedShippingFee\":null")))
                .andExpect(jsonPath("$.page").doesNotExist());
    }

    @Test void validationReturnsIssuesWithoutPaginationOnTheEnclosingObject() throws Exception {
        var issue = new CartIssue(UUID.randomUUID(), UUID.randomUUID(), CartIssue.Code.PRICE_CHANGED);
        when(service.validate(any())).thenReturn(new CartValidationResponse(false, List.of(issue)));
        mvc.perform(post("/api/v1/carts/me/validate"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.valid").value(false))
                .andExpect(jsonPath("$.data.issues[0].code").value("PRICE_CHANGED"))
                .andExpect(jsonPath("$.data.issues[0].itemId").value(issue.itemId().toString()))
                .andExpect(jsonPath("$.data.issues[0].skuId").value(issue.skuId().toString()))
                .andExpect(jsonPath("$.page").doesNotExist());
    }

    @Test void previewAcceptsShippingPaymentAndLegacyPromoCodeAndReturnsItems() throws Exception {
        var item = new CheckoutPreviewResponse.Item(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                2, new BigDecimal("150000"), new BigDecimal("300000"));
        when(service.previewCheckout(any(), argThat(request -> "D01".equals(request.promoCode()))))
                .thenReturn(new CheckoutPreviewResponse(List.of(item),
                        item.lineTotal(), new BigDecimal("30000"), BigDecimal.ZERO, BigDecimal.ZERO,
                        item.lineTotal().add(new BigDecimal("30000")), List.of(), List.of()));
        mvc.perform(post("/api/v1/checkout/preview").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("response success"))
                .andExpect(jsonPath("$.data.items[0].quantity").value(2))
                .andExpect(jsonPath("$.data.items[0].unitPrice").value(150000))
                .andExpect(jsonPath("$.data.total").value(330000))
                .andExpect(jsonPath("$.data.issues").isEmpty())
                .andExpect(jsonPath("$.data.shippingFee").value(30000))
                .andExpect(jsonPath("$.page").doesNotExist());
    }

    @Test void emptyCartPreviewReturns400CommerceProblemDetails() throws Exception {
        when(service.previewCheckout(any(), any())).thenThrow(new DomainException(CommerceErrorCode.CART_EMPTY));
        mvc.perform(post("/api/v1/checkout/preview").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("COMMERCE_CART_EMPTY"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.instance").value("/api/v1/checkout/preview"))
                .andExpect(jsonPath("$.type").isNotEmpty()).andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.detail").isNotEmpty()).andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test void malformedAndInvalidShippingRequestsDoNotInvokePricing() throws Exception {
        mvc.perform(post("/api/v1/checkout/preview").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"));
        mvc.perform(post("/api/v1/checkout/preview").contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.replace("\"SEPAY\"", "\"INVALID\"").replace("\"0901234567\"", "\"\"")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(2));
        verifyNoInteractions(service);
    }
}
