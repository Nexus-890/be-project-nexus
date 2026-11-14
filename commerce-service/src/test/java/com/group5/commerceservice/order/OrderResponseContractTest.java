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
        var order = new OrderResponse(id, "NX-TEST", user.id(), user.id(), OrderType.STANDARD,
                OrderStatus.PENDING, OffsetDateTime.now(), BigDecimal.TEN, List.of(), null, null,
                id, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false, null);
        var group = new OrderGroupResponse(id, "PENDING", BigDecimal.TEN, "VND", OffsetDateTime.now(), List.of(order));
        when(service.create(eq(user), eq("key-1"), any())).thenReturn(
                new OrderService.CreationResult(true, group), new OrderService.CreationResult(false, group));
        String request = """
                {"shippingAddress":{"recipientName":"Buyer","phoneNumber":"0900000000","addressLine":"1 Main St",
                "wardCode":"1003646","provinceCode":"1000001","countryCode":"VN"},"paymentMethod":"SEPAY"}
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

    @Test void historyPreservesNullableHistoricalReasonAndActor() throws Exception {
        var entry = new OrderStatusHistoryResponse(id, null, OrderStatus.PENDING, null, null,
                OffsetDateTime.parse("2026-11-12T01:00:00Z"));
        when(service.statusHistory(user, id)).thenReturn(List.of(entry));

        mvc.perform(get("/api/v1/orders/{id}/history", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].newStatus").value("PENDING"))
                .andExpect(jsonPath("$.data[0].reason").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data[0].changedBy").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data[0].createdAt").value("2026-11-12T01:00:00Z"));
    }

    @Test void orderReadsWrapTheOrderAndPutThePageBesideTheList() throws Exception {
        var order = new OrderResponse(id, "NX-TEST", user.id(), user.id(), OrderType.STANDARD,
                OrderStatus.PENDING, OffsetDateTime.now(), BigDecimal.TEN, List.of(), null, null,
                id, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, true,
                "Payment arrived after the payment deadline. Support will contact you about a refund.");
        when(service.getOrder(user, id)).thenReturn(order);
        mvc.perform(get("/api/v1/orders/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.orderId").value(id.toString()))
                .andExpect(jsonPath("$.data.canCancel").value(true))
                .andExpect(jsonPath("$.data.paymentNote").value(
                        "Payment arrived after the payment deadline. Support will contact you about a refund."))
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
        doThrow(new DomainException(CommerceErrorCode.FORBIDDEN)).when(service).getOrder(user, id);
        mvc.perform(get("/api/v1/orders/{id}", id))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("COMMERCE_FORBIDDEN"))
                .andExpect(jsonPath("$.detail").value("You do not have permission"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test void managementRouteBindsEveryFilterAndKeepsPageBesideData() throws Exception {
        UUID buyerId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        OffsetDateTime from = OffsetDateTime.parse("2026-11-01T00:00:00+07:00");
        OffsetDateTime to = OffsetDateTime.parse("2026-11-03T23:59:59+07:00");
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-11-02T08:15:30Z");
        var order = new OrderResponse(id, "NX-MANAGE", buyerId, sellerId, OrderType.AUCTION, OrderStatus.PAID,
                createdAt, new BigDecimal("150000.00"), List.of(), null, null,
                id, new BigDecimal("150000.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false, null);
        when(service.listManaged(user, buyerId, sellerId, OrderStatus.PAID, OrderType.AUCTION, from, to, 1, 2))
                .thenReturn(new PageImpl<>(List.of(order), PageRequest.of(1, 2), 3));

        mvc.perform(get("/api/v1/orders/manage")
                        .param("userId", buyerId.toString()).param("sellerId", sellerId.toString())
                        .param("status", "PAID").param("orderType", "AUCTION")
                        .param("from", from.toString()).param("to", to.toString())
                        .param("page", "1").param("size", "2"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.message").value("response success"))
                .andExpect(jsonPath("$.data[0].orderId").value(id.toString()))
                .andExpect(jsonPath("$.data[0].userId").value(buyerId.toString()))
                .andExpect(jsonPath("$.data[0].sellerId").value(sellerId.toString()))
                .andExpect(jsonPath("$.data[0].orderType").value("AUCTION"))
                .andExpect(jsonPath("$.data[0].createdAt").value(createdAt.toString()))
                .andExpect(jsonPath("$.page.number").value(1)).andExpect(jsonPath("$.page.size").value(2))
                .andExpect(jsonPath("$.page.totalElements").value(3)).andExpect(jsonPath("$.page.totalPages").value(2))
                .andExpect(jsonPath("$.data.content").doesNotExist());

        verify(service).listManaged(user, buyerId, sellerId, OrderStatus.PAID, OrderType.AUCTION, from, to, 1, 2);
        verify(service, never()).getOrder(any(), any());
    }

    @Test void salesRouteBindsItsFiltersWithoutAcceptingACallerSuppliedSellerScope() throws Exception {
        OffsetDateTime from = OffsetDateTime.parse("2026-11-01T00:00:00Z");
        OffsetDateTime to = OffsetDateTime.parse("2026-11-03T23:59:59Z");
        UUID buyerId = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-11-02T08:15:30Z");
        var order = new OrderResponse(id, "NX-SALES", buyerId, user.id(), OrderType.STANDARD, OrderStatus.PENDING,
                createdAt, new BigDecimal("150000.00"), List.of(), "Snapshot Buyer", "0901234567",
                id, new BigDecimal("150000.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false, null);
        when(service.listSales(user, OrderStatus.PENDING, from, to, 0, 5))
                .thenReturn(new PageImpl<>(List.of(order), PageRequest.of(0, 5), 1));

        mvc.perform(get("/api/v1/orders/sales").param("status", "PENDING")
                        .param("from", from.toString()).param("to", to.toString()).param("size", "5")
                        .param("sellerId", UUID.randomUUID().toString()).param("userId", UUID.randomUUID().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].orderId").value(id.toString()))
                .andExpect(jsonPath("$.data[0].userId").value(buyerId.toString()))
                .andExpect(jsonPath("$.data[0].orderType").value("STANDARD"))
                .andExpect(jsonPath("$.data[0].createdAt").value(createdAt.toString()))
                .andExpect(jsonPath("$.data[0].recipientName").value("Snapshot Buyer"))
                .andExpect(jsonPath("$.data[0].phoneNumber").value("0901234567"))
                .andExpect(jsonPath("$.page.number").value(0)).andExpect(jsonPath("$.page.size").value(5))
                .andExpect(jsonPath("$.page.totalElements").value(1)).andExpect(jsonPath("$.page.totalPages").value(1));

        verify(service).listSales(user, OrderStatus.PENDING, from, to, 0, 5);
        verify(service, never()).getOrder(any(), any());
    }

    @Test void operationalListsDefaultToPageZeroSizeTenAndReturnEmptyArrays() throws Exception {
        var empty = new PageImpl<OrderResponse>(List.of(), PageRequest.of(0, 10), 0);
        when(service.listManaged(user, null, null, null, null, null, null, 0, 10)).thenReturn(empty);
        when(service.listSales(user, null, null, null, 0, 10)).thenReturn(empty);

        for (String route : List.of("/api/v1/orders/manage", "/api/v1/orders/sales")) {
            mvc.perform(get(route)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.message").value("response success"))
                    .andExpect(jsonPath("$.data").isArray()).andExpect(jsonPath("$.data").isEmpty())
                    .andExpect(jsonPath("$.page.number").value(0)).andExpect(jsonPath("$.page.size").value(10))
                    .andExpect(jsonPath("$.page.totalElements").value(0)).andExpect(jsonPath("$.page.totalPages").value(0));
        }
        verify(service).listManaged(user, null, null, null, null, null, null, 0, 10);
        verify(service).listSales(user, null, null, null, 0, 10);
        verify(service, never()).getOrder(any(), any());
    }

    @Test void malformedListFiltersReturnProblemDetailsWithoutCallingTheService() throws Exception {
        for (String[] filter : new String[][]{
                {"userId", "not-a-uuid"}, {"sellerId", "not-a-uuid"}, {"status", "SHIPPING"},
                {"orderType", "UNKNOWN"}, {"from", "2026-11-01"}, {"to", "not-a-date"},
                {"page", "first"}, {"size", "ten"}}) {
            mvc.perform(get("/api/v1/orders/manage").param(filter[0], filter[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                    .andExpect(jsonPath("$.instance").value("/api/v1/orders/manage"))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
        for (String[] filter : new String[][]{
                {"status", "SHIPPING"}, {"from", "not-a-date"}, {"to", "2026-11-01"},
                {"page", "first"}, {"size", "ten"}}) {
            mvc.perform(get("/api/v1/orders/sales").param(filter[0], filter[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                    .andExpect(jsonPath("$.instance").value("/api/v1/orders/sales"))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
        verifyNoInteractions(service);
    }

    @Test void invalidListRangesExposeTheCommerceFilterError() throws Exception {
        OffsetDateTime from = OffsetDateTime.parse("2026-11-03T00:00:00Z");
        OffsetDateTime to = OffsetDateTime.parse("2026-11-01T00:00:00Z");
        when(service.listManaged(user, null, null, null, null, from, to, 0, 10))
                .thenThrow(new DomainException(CommerceErrorCode.INVALID_ORDER_FILTER));
        when(service.listSales(user, null, from, to, 0, 10))
                .thenThrow(new DomainException(CommerceErrorCode.INVALID_ORDER_FILTER));

        for (String route : List.of("/api/v1/orders/manage", "/api/v1/orders/sales")) {
            mvc.perform(get(route).param("from", from.toString()).param("to", to.toString()))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("COMMERCE_INVALID_ORDER_FILTER"))
                    .andExpect(jsonPath("$.instance").value(route))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
    }

    @Test void cancellationWrapsTheGroupAndNamesItsFailures() throws Exception {
        var order = new OrderResponse(id, "NX-TEST", user.id(), user.id(), OrderType.STANDARD,
                OrderStatus.CANCELED, OffsetDateTime.now(), BigDecimal.TEN, List.of(), null, null,
                id, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false, null);
        var group = new OrderGroupResponse(id, "CANCELED", BigDecimal.TEN, "VND", OffsetDateTime.now(), List.of(order));
        when(service.cancel(eq(user), eq(id), any())).thenReturn(group);
        mvc.perform(post("/api/v1/orders/{id}/cancel", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Changed my mind\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("CANCELED"))
                .andExpect(jsonPath("$.data.orders[0].status").value("CANCELED"))
                .andExpect(jsonPath("$.data.orders[0].canCancel").value(false));
        mvc.perform(post("/api/v1/orders/{id}/cancel", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\" \"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        when(service.cancel(eq(user), eq(id), any()))
                .thenThrow(new DomainException(CommerceErrorCode.ORDER_NOT_CANCELLABLE,
                        "NX-TEST is SHIPPED and can no longer be cancelled"));
        mvc.perform(post("/api/v1/orders/{id}/cancel", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Changed my mind\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COMMERCE_ORDER_NOT_CANCELLABLE"))
                .andExpect(jsonPath("$.detail").value("NX-TEST is SHIPPED and can no longer be cancelled"));
    }

    // Không gọi service khi mã địa giới bắt buộc thiếu, tránh tạo đơn trước bước validation.
    @Test void missingWardCodeIs400BeforeCreatingAnyOrder() throws Exception {
        mvc.perform(post("/api/v1/orders").header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"shippingAddress":{"recipientName":"Buyer","phoneNumber":"0900000138",
                        "addressLine":"138 Street","provinceCode":"1000001","countryCode":"VN"},"paymentMethod":"SEPAY"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[0].field").value("shippingAddress.wardCode"));
        verifyNoInteractions(service);
    }

    // Kiểm tra envelope của group và giữ đủ thông tin SKU/count trong lỗi tồn kho.
    @Test void groupEndpointAndStockProblemKeepThePublishedContract() throws Exception {
        var group = new OrderGroupResponse(id, "PENDING", BigDecimal.TEN, "VND", OffsetDateTime.now(), List.of());
        when(service.getGroup(user, id)).thenReturn(group);
        mvc.perform(get("/api/v1/orders/groups/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderGroupId").value(id.toString()))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.page").doesNotExist());
        when(service.create(any(), any(), any())).thenThrow(new DomainException(CommerceErrorCode.INSUFFICIENT_STOCK,
                "SKU has one available", java.util.Map.of("skuId", id, "available", 1, "requested", 2)));
        mvc.perform(post("/api/v1/orders").header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"shippingAddress":{"recipientName":"Buyer","phoneNumber":"0900000138",
                        "addressLine":"138 Street","wardCode":"1003646","provinceCode":"1000001","countryCode":"VN"},"paymentMethod":"SEPAY"}
                        """))
                .andExpect(status().isConflict()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("COMMERCE_INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.skuId").value(id.toString())).andExpect(jsonPath("$.available").value(1))
                .andExpect(jsonPath("$.requested").value(2));
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
