package com.group5.commerceservice.cart;

import com.group5.commerceservice.cart.controller.CartController;
import com.group5.commerceservice.cart.dto.response.CartResponse;
import com.group5.commerceservice.cart.service.CartService;
import com.group5.commerceservice.cart.service.CartPricingService;
import com.group5.commerceservice.shared.exception.CommerceErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.common_web.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.containsInAnyOrder;

class CartResponseContractTest {
    private final CartService service = mock(CartService.class);
    private final UUID id = UUID.randomUUID();
    private MockMvc mvc;
    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new CartController(service, mock(CartPricingService.class)))
                .setControllerAdvice(new GlobalExceptionHandler(null)).build();
    }
    @Test void bothCartOperationsWrapTheirResultAndKeepStatus() throws Exception {
        CartResponse cart = new CartResponse(id, UUID.randomUUID(), "ACTIVE", List.of());
        when(service.getMyCart(any())).thenReturn(cart);
        when(service.addItem(any(), any())).thenReturn(cart);
        mvc.perform(get("/api/v1/carts/me")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id.toString())).andExpect(jsonPath("$.id").doesNotExist());
        mvc.perform(post("/api/v1/carts/me/items").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skuId\":\"" + id + "\",\"quantity\":1}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.page").doesNotExist());
    }
    @Test void failedFieldsAndBusinessCodesAreExposed() throws Exception {
        mvc.perform(post("/api/v1/carts/me/items").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":0}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("skuId", "quantity")))
                .andExpect(jsonPath("$.errors[0].code").isNotEmpty()).andExpect(jsonPath("$.errors[0].detail").isNotEmpty());
        verifyNoInteractions(service);
        when(service.getMyCart(any())).thenThrow(new DomainException(CommerceErrorCode.UNAUTHENTICATED));
        mvc.perform(get("/api/v1/carts/me")).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("COMMERCE_UNAUTHENTICATED"));
    }
}
