package com.group5.shipmentservice.shipping.service;

import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import com.group5.shipmentservice.shipping.entity.Shipment;
import com.group5.shipmentservice.shipping.enums.ShipmentStatus;
import com.group5.shipmentservice.shipping.repository.ShipmentRepository;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShipmentReadServiceTest {
    private static final UUID BUYER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID OTHER_BUYER = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID SHIPMENT = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID ORDER = UUID.fromString("44444444-4444-4444-8444-444444444444");

    @Mock
    private ShipmentRepository shipments;
    private ShipmentReadService service;

    @BeforeEach
    void setUp() {
        service = new ShipmentReadService(shipments);
    }

    @Test
    void buyerReadsOwnShipment() {
        Shipment shipment = mock(Shipment.class);
        when(shipment.getId()).thenReturn(SHIPMENT);
        when(shipment.getBuyerId()).thenReturn(BUYER);
        when(shipments.findById(SHIPMENT)).thenReturn(Optional.of(shipment));

        assertThat(service.get(user("BUYER"), SHIPMENT).id()).isEqualTo(SHIPMENT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"BUYER", "SELLER"})
    void regularUserCannotReadAnotherBuyersShipment(String role) {
        Shipment shipment = mock(Shipment.class);
        when(shipment.getBuyerId()).thenReturn(OTHER_BUYER);
        when(shipments.findById(SHIPMENT)).thenReturn(Optional.of(shipment));

        assertThatThrownBy(() -> service.get(user(role), SHIPMENT))
                .isInstanceOfSatisfying(DomainException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo("SHIPMENT_NOT_FOUND"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "SUPPORT_STAFF"})
    void authorizedStaffCanReadOtherBuyersShipment(String role) {
        Shipment shipment = mock(Shipment.class);
        when(shipment.getId()).thenReturn(SHIPMENT);
        when(shipments.findById(SHIPMENT)).thenReturn(Optional.of(shipment));

        assertThat(service.get(user(role), SHIPMENT).id()).isEqualTo(SHIPMENT);
    }

    @Test
    void missingShipmentHasSameNotFoundCodeAsHiddenShipment() {
        when(shipments.findById(SHIPMENT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(user("BUYER"), SHIPMENT))
                .isInstanceOfSatisfying(DomainException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo("SHIPMENT_NOT_FOUND"));
    }

    @Test
    void unauthenticatedCallerDoesNotReadRepository() {
        assertThatThrownBy(() -> service.get(null, SHIPMENT))
                .isInstanceOfSatisfying(DomainException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo("SHIPMENT_UNAUTHENTICATED"));
        assertThatThrownBy(() -> service.list(null, null, null, 0, 20))
                .isInstanceOf(DomainException.class);
        verifyNoInteractions(shipments);
    }

    @ParameterizedTest
    @CsvSource({"-1,20", "0,0", "0,-1", "0,101"})
    void rejectsInvalidPageBeforeQuerying(int page, int size) {
        assertThatThrownBy(() -> service.list(user("BUYER"), null, null, page, size))
                .isInstanceOfSatisfying(DomainException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo("SHIPMENT_INVALID_PARAMETER"));
        verifyNoInteractions(shipments);
    }

    @Test
    @SuppressWarnings("unchecked")
    void buyerOwnershipAndRequestedFiltersApplyInsidePagedRepositoryQuery() {
        PageRequest page = PageRequest.of(1, 7, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        when(shipments.findAll(any(Specification.class), eq(page))).thenReturn(new PageImpl<>(List.of(), page, 3));

        Page<?> result = service.list(user("BUYER"), ORDER, ShipmentStatus.PENDING_TRACKING, 1, 7);

        ArgumentCaptor<Specification<Shipment>> scope = ArgumentCaptor.forClass(Specification.class);
        verify(shipments).findAll(scope.capture(), eq(page));
        Root<Shipment> root = mock(Root.class);
        CriteriaBuilder criteria = mock(CriteriaBuilder.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        Path<Object> buyerPath = mock(Path.class);
        Path<Object> orderPath = mock(Path.class);
        Path<Object> statusPath = mock(Path.class);
        Predicate predicate = mock(Predicate.class);
        when(root.get("buyerId")).thenReturn(buyerPath);
        when(root.get("orderId")).thenReturn(orderPath);
        when(root.get("shipmentStatus")).thenReturn(statusPath);
        when(criteria.conjunction()).thenReturn(predicate);
        when(criteria.equal(any(), any(Object.class))).thenReturn(predicate);
        when(criteria.and(any(Predicate.class), any(Predicate.class))).thenReturn(predicate);

        scope.getValue().toPredicate(root, query, criteria);

        verify(criteria).equal(buyerPath, BUYER);
        verify(criteria).equal(orderPath, ORDER);
        verify(criteria).equal(statusPath, ShipmentStatus.PENDING_TRACKING);
        assertThat(result.getNumber()).isEqualTo(1);
        assertThat(result.getSize()).isEqualTo(7);
        assertThat(result.getTotalElements()).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "SUPPORT_STAFF"})
    @SuppressWarnings("unchecked")
    void staffListUsesUnrestrictedRepositoryScope(String role) {
        when(shipments.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());

        service.list(user(role), null, null, 0, 20);

        ArgumentCaptor<Specification<Shipment>> scope = ArgumentCaptor.forClass(Specification.class);
        verify(shipments).findAll(scope.capture(), any(Pageable.class));
        Root<Shipment> root = mock(Root.class);
        CriteriaBuilder criteria = mock(CriteriaBuilder.class);
        scope.getValue().toPredicate(root, mock(CriteriaQuery.class), criteria);
        verify(root, never()).get("buyerId");
        verify(criteria).conjunction();
    }

    private static CurrentUser user(String role) {
        return new CurrentUser(BUYER, Set.of(role), Set.of("SHIPPING.VIEW_SHIPMENT"));
    }
}
