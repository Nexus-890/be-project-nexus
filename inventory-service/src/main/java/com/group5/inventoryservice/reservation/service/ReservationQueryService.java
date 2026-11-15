package com.group5.inventoryservice.reservation.service;

import com.group5.inventoryservice.reservation.ReservationStatus;
import com.group5.inventoryservice.reservation.dto.response.ReservationView;
import com.group5.inventoryservice.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationQueryService {

    private static final Sort ORDER = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final ReservationRepository reservations;

    public List<OrderReservation> byOrder(UUID orderId, UUID buyerId) {
        return reservations.findByOrderIdAndAuctionWinnerIdOrderById(orderId, buyerId).stream()
                .map(hold -> new OrderReservation(hold.getId(), hold.getWarehouseId(), hold.getStatus()))
                .toList();
    }

    public record OrderReservation(UUID reservationId, UUID warehouseId, ReservationStatus status) {
    }

    public Page<ReservationView> list(UUID orderId, UUID skuId, ReservationStatus status, int page, int size) {
        return reservations.search(orderId, skuId, status == null ? null : status.name(), PageRequest.of(page, size, ORDER))
                .map(ReservationView::from);
    }
}
