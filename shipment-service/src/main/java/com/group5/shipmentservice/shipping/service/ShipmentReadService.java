package com.group5.shipmentservice.shipping.service;

import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import com.group5.shipmentservice.shared.exception.ShipmentErrorCode;
import com.group5.shipmentservice.shipping.dto.response.ShipmentResponse;
import com.group5.shipmentservice.shipping.entity.Shipment;
import com.group5.shipmentservice.shipping.enums.ShipmentStatus;
import com.group5.shipmentservice.shipping.repository.ShipmentRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ShipmentReadService {
    private final ShipmentRepository shipments;

    public ShipmentReadService(ShipmentRepository shipments) {
        this.shipments = shipments;
    }

    public ShipmentResponse get(CurrentUser user, UUID id) {
        requireUser(user);
        Shipment shipment = shipments.findById(id)
                .orElseThrow(() -> new DomainException(ShipmentErrorCode.NOT_FOUND));
        if (!canManage(user) && !user.id().equals(shipment.getBuyerId())) {
            throw new DomainException(ShipmentErrorCode.NOT_FOUND);
        }
        return ShipmentResponse.from(shipment);
    }

    public Page<ShipmentResponse> list(CurrentUser user, UUID orderId, ShipmentStatus status, int page, int size) {
        requireUser(user);
        if (page < 0 || size < 1 || size > 100) {
            throw new DomainException(ShipmentErrorCode.INVALID_PARAMETER);
        }
        Specification<Shipment> scope = (root, query, criteria) -> criteria.conjunction();
        if (!canManage(user)) {
            scope = scope.and((root, query, criteria) -> criteria.equal(root.get("buyerId"), user.id()));
        }
        if (orderId != null) {
            scope = scope.and((root, query, criteria) -> criteria.equal(root.get("orderId"), orderId));
        }
        if (status != null) {
            scope = scope.and((root, query, criteria) -> criteria.equal(root.get("shipmentStatus"), status));
        }
        return shipments.findAll(scope, PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt", "id"))).map(ShipmentResponse::from);
    }

    private static boolean canManage(CurrentUser user) {
        return user.hasRole("ADMIN") || user.hasRole("SUPPORT_STAFF");
    }

    private static void requireUser(CurrentUser user) {
        if (user == null || user.id() == null) {
            throw new DomainException(ShipmentErrorCode.UNAUTHENTICATED);
        }
    }
}
