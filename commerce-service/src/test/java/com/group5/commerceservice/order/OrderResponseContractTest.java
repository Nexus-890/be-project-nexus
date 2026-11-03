package com.group5.commerceservice.order;

import com.group5.commerceservice.order.controller.OrderController;
import com.group5.commerceservice.order.dto.response.OrderGroupResponse;
import com.group5.commerceservice.order.dto.response.OrderResponse;
import com.group5.commerceservice.order.dto.response.OrderStatusHistoryResponse;
import com.group5.commerceservice.order.service.OrderService;
import com.group5.commerceservice.shared.exception.CommerceErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import com.group5.common_web.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class OrderResponseContractTest {
    private final OrderService service = mock(OrderService.class);
    private final UUID id = UUID.randomUUID();
    private final CurrentUser user = new CurrentUser(UUID.randomUUID(), Set.of("ADMIN"), Set.of("ORDER.VIEW"));
    private MockMvc mvc;

    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new OrderController(service))
                .setControllerAdvice(new GlobalExceptionHandler(null))
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    @Override public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.getParameterType() == CurrentUser.class;
                    }
                    @Override public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                            NativeWebRequest request, WebDataBinderFactory factory) { return user; }
                }).build();
    }

    @Test void orderOperationsWrapResultsAndPreserveCreationReplayAndListSemantics() throws Exception {
        var order = new OrderResponse(id, "NX-TEST", user.id(), OrderStatus.PENDING, BigDecimal.TEN, List.of());
        var group = new OrderGroupResponse(id, "PENDING", BigDecimal.TEN, "VND", OffsetDateTime.now(), List.of(order));
        when(service.create(eq(user), eq("key-1"), any())).thenReturn(
                new OrderService.CreationResult(true, group), new OrderService.CreationResult(false, group));
        String request = """
                {"shippingAddress":{"recipientName":"Buyer","phoneNumber":"0900000000","addressLine":"1 Main St",
                "ward":"Ward 1","district":"District 1","city":"Ho Chi Minh","countryCode":"VN"},"paymentMethod":"SEPAY"}
                """;
        for (int status : List.of(201, 200)) {
            mvc.perform(post("/api/v1/orders").header("Idempotency-Key", "key-1")
                            .contentType(MediaType.APPLICATION_JSON).content(request))
                    .andExpect(status().is(status)).andExpect(jsonPath("$.data.orderGroupId").value(id.toString()))
                    .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.message").value("response success"))
                    .andExpect(jsonPath("$.page").doesNotExist());
        }
        when(service.changeStatus(eq(user), eq(id), any())).thenReturn(order);
        mvc.perform(patch("/api/v1/orders/{id}/status", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAID\",\"reason\":\"Paid\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.orderId").value(id.toString()));
        var history = new OrderStatusHistoryResponse(id, null, OrderStatus.PENDING, "Created", user.id(), OffsetDateTime.now());
        when(service.statusHistory(user, id)).thenReturn(List.of(history), List.of());
        mvc.perform(get("/api/v1/orders/{id}/history", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(id.toString()))
                .andExpect(jsonPath("$.page.totalElements").value(1));
        mvc.perform(get("/api/v1/orders/{id}/history", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty()).andExpect(jsonPath("$.page.size").value(1))
                .andExpect(jsonPath("$.page.totalPages").value(0));
        when(service.hasProductReferences(id)).thenReturn(true);
        mvc.perform(get("/internal/order-references/products/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasOrders").value(true)).andExpect(jsonPath("$.code").value(0));
    }

    @Test void orderReadsWrapTheOrderAndPutThePageBesideTheList() throws Exception {
        var order = new OrderResponse(id, "NX-TEST", user.id(), OrderStatus.PENDING, BigDecimal.TEN, List.of());
        when(service.getOrder(user, id)).thenReturn(order);
        mvc.perform(get("/api/v1/orders/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.orderId").value(id.toString()))
                .andExpect(jsonPath("$.page").doesNotExist());
        when(service.listOwn(user, OrderStatus.PENDING, 1, 2))
                .thenReturn(new PageImpl<>(List.of(order), PageRequest.of(1, 2), 3));
        mvc.perform(get("/api/v1/orders").param("status", "PENDING").param("page", "1").param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].orderId").value(id.toString()))
                .andExpect(jsonPath("$.page.number").value(1)).andExpect(jsonPath("$.page.size").value(2))
                .andExpect(jsonPath("$.page.totalElements").value(3)).andExpect(jsonPath("$.page.totalPages").value(2));
        mvc.perform(get("/api/v1/orders").param("status", "SHIPPING"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
        when(service.getOrder(user, id)).thenThrow(new DomainException(CommerceErrorCode.ORDER_NOT_FOUND));
        mvc.perform(get("/api/v1/orders/{id}", id))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("COMMERCE_ORDER_NOT_FOUND"));
    }

    @Test void cancellationWrapsTheGroupAndNamesItsFailures() throws Exception {
        var order = new OrderResponse(id, "NX-TEST", user.id(), OrderStatus.CANCELED, BigDecimal.TEN, List.of());
        var group = new OrderGroupResponse(id, "CANCELED", BigDecimal.TEN, "VND", OffsetDateTime.now(), List.of(order));
        when(service.cancel(eq(user), eq(id), any())).thenReturn(group);
        mvc.perform(post("/api/v1/orders/{id}/cancel", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Changed my mind\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("CANCELED"))
                .andExpect(jsonPath("$.data.orders[0].status").value("CANCELED"));
        mvc.perform(post("/api/v1/orders/{id}/cancel", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\" \"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        when(service.cancel(eq(user), eq(id), any()))
                .thenThrow(new DomainException(CommerceErrorCode.ORDER_NOT_CANCELLABLE));
        mvc.perform(post("/api/v1/orders/{id}/cancel", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Changed my mind\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COMMERCE_ORDER_NOT_CANCELLABLE"));
    }

    @Test void transitionFailuresExposeNamedProblemDetails() throws Exception {
        when(service.changeStatus(eq(user), eq(id), any())).thenThrow(new DomainException(CommerceErrorCode.ILLEGAL_TRANSITION));
        mvc.perform(patch("/api/v1/orders/{id}/status", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAID\",\"reason\":\"Paid\"}"))
                .andExpect(status().isConflict()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("COMMERCE_ILLEGAL_TRANSITION"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
