package com.group5.commerceservice.promotion;

import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static com.group5.commerceservice.promotion.MegaSaleEvent.*;
import static com.group5.commerceservice.shared.exception.CommerceErrorCode.*;

@Service
@Transactional(readOnly = true)
class MegaSaleEventService {
    private final MegaSaleEventRepository events;

    MegaSaleEventService(MegaSaleEventRepository events) {
        this.events = events;
    }

    Page<MegaSaleEvent> list(CurrentUser user, Status status, int page, int size) {
        return events.list(visibleSeller(user), status, now(), page(page, size));
    }

    MegaSaleEvent find(CurrentUser user, UUID id) {
        return events.visible(id, visibleSeller(user), now())
                .orElseThrow(() -> new DomainException(PROMOTION_EVENT_NOT_FOUND));
    }

    @Transactional
    MegaSaleEvent create(UUID actor, Write request) {
        validateTimeline(request);
        return lock(events.create(actor, request));
    }

    @Transactional
    MegaSaleEvent edit(UUID actor, UUID id, Write request) {
        validateTimeline(request);
        if (lock(id).status() != Status.DRAFT) throw new DomainException(PROMOTION_ILLEGAL_TRANSITION);
        events.edit(id, actor, request, now());
        return lock(id);
    }

    @Transactional
    MegaSaleEvent status(UUID actor, UUID id, Action action) {
        MegaSaleEvent event = lock(id);
        OffsetDateTime now = now();
        Status next = switch (action) {
            case OPEN -> {
                if (event.status() != Status.DRAFT || !now.isBefore(event.registrationEndAt())) {
                    throw new DomainException(PROMOTION_ILLEGAL_TRANSITION);
                }
                yield now.isBefore(event.registrationStartAt()) ? Status.DRAFT : Status.REGISTRATION_OPEN;
            }
            case CANCEL -> {
                if (event.status() == Status.CANCELED || !now.isBefore(event.endAt())) {
                    throw new DomainException(PROMOTION_ILLEGAL_TRANSITION);
                }
                yield Status.CANCELED;
            }
        };
        events.status(id, actor, next, action == Action.OPEN, now);
        return lock(id);
    }

    @Transactional
    Seller register(UUID seller, UUID id) {
        MegaSaleEvent event = lock(id);
        OffsetDateTime now = now();
        if (!event.acceptingRegistrations(now)) throw new DomainException(PROMOTION_REGISTRATION_CLOSED);
        return events.register(id, seller, now).orElseThrow(() -> new DomainException(PROMOTION_ALREADY_REGISTERED));
    }

    @Transactional
    void withdraw(UUID seller, UUID id) {
        MegaSaleEvent event = lock(id);
        OffsetDateTime now = now();
        if (event.status() == Status.CANCELED || !now.isBefore(event.startAt())) {
            throw new DomainException(PROMOTION_ILLEGAL_TRANSITION);
        }
        if (!events.withdraw(id, seller, now)) throw new DomainException(PROMOTION_REGISTRATION_NOT_FOUND);
    }

    Page<Seller> sellers(UUID id, SellerStatus status, int page, int size) {
        events.visible(id, null, now()).orElseThrow(() -> new DomainException(PROMOTION_EVENT_NOT_FOUND));
        return events.sellers(id, status, page(page, size));
    }

    @Transactional
    Seller review(UUID actor, UUID id, Review request) {
        Seller registration = events.registration(id)
                .orElseThrow(() -> new DomainException(PROMOTION_REGISTRATION_NOT_FOUND));
        MegaSaleEvent event = lock(registration.eventId());
        OffsetDateTime now = now();
        if (event.status() == Status.CANCELED || !now.isBefore(event.endAt())) {
            throw new DomainException(PROMOTION_ILLEGAL_TRANSITION);
        }
        return events.review(id, actor, request, now)
                .orElseThrow(() -> new DomainException(PROMOTION_ILLEGAL_TRANSITION));
    }

    @Transactional
    int advance(OffsetDateTime now) {
        return events.advance(now);
    }

    private MegaSaleEvent lock(UUID id) {
        return events.lock(id).orElseThrow(() -> new DomainException(PROMOTION_EVENT_NOT_FOUND));
    }

    private static UUID visibleSeller(CurrentUser user) {
        return user.hasRole("ADMIN") ? null : user.id();
    }

    private static PageRequest page(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new DomainException(PROMOTION_INVALID_REQUEST);
        return PageRequest.of(page, size);
    }

    private static void validateTimeline(Write r) {
        if (!r.registrationEndAt().isAfter(r.registrationStartAt()) || r.registrationEndAt().isAfter(r.startAt())
                || !r.endAt().isAfter(r.startAt())) {
            throw new DomainException(PROMOTION_INVALID_REQUEST,
                    "Registration must end before the event starts, and the event must end after it starts");
        }
    }

    static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
