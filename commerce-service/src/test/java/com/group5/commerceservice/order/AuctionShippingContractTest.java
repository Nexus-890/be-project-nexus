package com.group5.commerceservice.order;

import com.group5.common_core.model.ApiResponse;
import com.group5.common_security.CurrentUser;
import com.group5.commerceservice.cart.dto.*;
import com.group5.commerceservice.order.client.*;
import com.group5.commerceservice.order.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.group5.common_web.exception.GlobalExceptionHandler;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

import static com.group5.commerceservice.order.AuctionSettlementTest.ADDRESS;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitConfig(AuctionShippingContractTest.Config.class)
class AuctionShippingContractTest {
    @Autowired AuctionShippingController controller;
    @Autowired AuctionShippingService service;
    @AfterEach void clear() { SecurityContextHolder.clearContext();reset(service); }

    @Test void requiresBothBuyerAndCheckoutPrivilege() {
        for (String[] authorities : List.of(new String[]{"ROLE_BUYER"},new String[]{"CHECKOUT.START"},new String[]{"ROLE_ADMIN","CHECKOUT.START"})) {
            authenticate(authorities);
            assertThatThrownBy(() -> controller.confirm(null,UUID.randomUUID(),ADDRESS)).isInstanceOf(AccessDeniedException.class);
        }
        verifyNoInteractions(service);
        authenticate("ROLE_BUYER","CHECKOUT.START");
        controller.confirm(null,UUID.randomUUID(),ADDRESS);verify(service).confirm(any(),any(),any());
    }

    @Test void httpReturnsApiResponseAndRejectsInvalidAddress() throws Exception {
        // HTTP validation/serialization test; method security is exercised separately above.
        var mvc=MockMvcBuilders.standaloneSetup(new AuctionShippingController(service))
                .setControllerAdvice(new GlobalExceptionHandler(null))
                .setCustomArgumentResolvers(new org.springframework.web.method.support.HandlerMethodArgumentResolver() {
                    public boolean supportsParameter(org.springframework.core.MethodParameter parameter) { return parameter.getParameterType()==CurrentUser.class; }
                    public Object resolveArgument(org.springframework.core.MethodParameter parameter,
                            org.springframework.web.method.support.ModelAndViewContainer container,
                            org.springframework.web.context.request.NativeWebRequest request,
                            org.springframework.web.bind.support.WebDataBinderFactory factory) {
                        return new CurrentUser(UUID.randomUUID(),Set.of("BUYER"),Set.of("CHECKOUT.START"));
                    }
                }).build();
        UUID id=UUID.randomUUID(), group=UUID.randomUUID();
        when(service.confirm(any(),any(),any())).thenReturn(new AuctionShippingService.Confirmation(group,new BigDecimal("0.00"),new BigDecimal("1500000.00"),OffsetDateTime.parse("2031-01-02T00:00:00Z")));
        String body=AuctionSettlementTest.JSON.writeValueAsString(ADDRESS);
        mvc.perform(post("/api/v1/orders/{id}/shipping-confirmation",id).contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.orderGroupId").value(group.toString()));
        mvc.perform(post("/api/v1/orders/{id}/shipping-confirmation",id).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @ParameterizedTest @CsvSource({"999999.00,1500000.00,30000.00","1000000.00,1.00,0.00","1500000.00,1.00,0.00"})
    void auctionFinalPriceControlsFreeShipping(String finalPrice,String catalogPrice,String expectedFee) {
        var catalog=mock(CatalogClient.class);var shipping=mock(OrderShippingService.class);
        var pricing=new PricingService(catalog,shipping);
        UUID skuId=UUID.randomUUID(),product=UUID.randomUUID(),warehouse=UUID.randomUUID();
        var sku=new CatalogSkuResponse();sku.setId(skuId);sku.setProductId(product);sku.setPrice(new BigDecimal(catalogPrice));
        when(catalog.getSkuById(skuId)).thenReturn(ApiResponse.of(sku));
        when(shipping.quoteAuction(any(),any(),any())).thenReturn(new OrderShippingService.Quote(new BigDecimal("30000.00"),new OrderShippingService.Parcel(1200,30,20,15),"GHN","STANDARD",OffsetDateTime.now()));
        var quote=pricing.auctionShippingQuote(ADDRESS,skuId,product,warehouse,new BigDecimal(finalPrice));
        assertThat(quote.fee()).isEqualByComparingTo(expectedFee);
        verify(shipping).quoteAuction(eq(ADDRESS),any(),eq(warehouse));
    }

    void authenticate(String... authorities) { SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
            new CurrentUser(UUID.randomUUID(),Set.of(),Set.of()),"",AuthorityUtils.createAuthorityList(authorities))); }
    @Configuration(proxyBeanMethods=false) @EnableMethodSecurity
    static class Config {
        @Bean AuctionShippingService service() { return mock(AuctionShippingService.class); }
        @Bean AuctionShippingController controller(AuctionShippingService service) { return new AuctionShippingController(service); }
    }
}
