package com.group5.userservice.seller;

import com.group5.common_core.constant.CommonErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import com.group5.userservice.iam.KeycloakAdminClient;
import com.group5.userservice.iam.UserEventPublisher;
import com.group5.userservice.iam.UserRepository;
import com.group5.userservice.iam.entity.User;
import com.group5.userservice.shared.exception.UserErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class SellerApplicationService {
    private static final Logger log = LoggerFactory.getLogger(SellerApplicationService.class);
    private static final String SELLER = "SELLER";
    private static final Sort QUEUE_ORDER = Sort.by("appliedAt", "userId");

    private final UserRepository users;
    private final SellerRepository sellers;
    private final KeycloakAdminClient keycloak;
    private final UserEventPublisher events;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public SellerApplicationService(UserRepository users, SellerRepository sellers, KeycloakAdminClient keycloak,
                                    UserEventPublisher events, Clock clock, PlatformTransactionManager manager) {
        this.users = users;
        this.sellers = sellers;
        this.keycloak = keycloak;
        this.events = events;
        this.clock = clock;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public SellerApplicationResponse apply(CurrentUser currentUser, SellerApplicationRequest request) {
        UUID userId = subject(currentUser);
        if (currentUser.hasRole(SELLER)) throw new DomainException(UserErrorCode.SELLER_APPLICATION_EXISTS);
        User user = lockUser(userId);
        Seller seller = sellers.findById(user.getId()).orElse(null);
        if (seller == null) {
            seller = new Seller();
            seller.setUserId(user.getId());
        } else if (seller.getStatus() != SellerStatus.REJECTED) {
            throw new DomainException(UserErrorCode.SELLER_APPLICATION_EXISTS);
        }
        seller.setCompanyName(request.companyName());
        seller.setContactPhone(request.phone());
        seller.setApplicationNote(request.note());
        seller.setStatus(SellerStatus.PENDING);
        seller.setRejectionReason(null);
        seller.setReviewedBy(null);
        seller.setReviewedAt(null);
        seller.setAppliedAt(OffsetDateTime.now(clock));
        return SellerApplicationResponse.from(sellers.saveAndFlush(seller));
    }

    @Transactional(readOnly = true)
    public Optional<SellerApplicationResponse> mine(CurrentUser currentUser) {
        return sellers.findById(subject(currentUser)).map(SellerApplicationResponse::from);
    }

    @Transactional(readOnly = true)
    public Page<SellerApplicationResponse> list(SellerStatus status, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new DomainException(CommonErrorCode.INVALID_PARAMETER);
        PageRequest request = PageRequest.of(page, size, QUEUE_ORDER);
        Page<Seller> result = status == null ? sellers.findAll(request) : sellers.findByStatus(status, request);
        return result.map(SellerApplicationResponse::from);
    }

    public SellerApplicationResponse approve(UUID userId, UUID actor) {
        KeycloakAdminClient.RealmRole sellerRole = keycloak.realmRole(SELLER)
                .orElseThrow(() -> new DomainException(UserErrorCode.USER_ROLE_NOT_FOUND));
        AtomicReference<Runnable> undo = new AtomicReference<>();
        try {
            return transaction.execute(status -> {
                Seller seller = pending(userId);
                List<KeycloakAdminClient.RealmRole> previous = keycloak.assignedRealmRoles(userId).stream()
                        .filter(role -> KeycloakAdminClient.APPLICATION_ROLES.contains(role.name())).toList();
                List<KeycloakAdminClient.RealmRole> next = List.copyOf(Stream.concat(previous.stream(), Stream.of(sellerRole))
                        .collect(Collectors.toMap(KeycloakAdminClient.RealmRole::name, role -> role,
                                (first, second) -> first, TreeMap::new)).values());
                seller.setStatus(SellerStatus.ACTIVE);
                seller.setReviewedBy(actor);
                seller.setReviewedAt(OffsetDateTime.now(clock));
                sellers.saveAndFlush(seller);
                List<String> previousNames = names(previous);
                List<String> nextNames = names(next);
                if (!previousNames.equals(nextNames)) {
                    undo.set(() -> keycloak.replaceRealmRoles(userId, previous));
                    keycloak.replaceRealmRoles(userId, next);
                    events.publish("UserRoleAssigned", userId, actor,
                            Map.of("previousRoles", previousNames, "roles", nextNames));
                }
                events.publish("SellerApplicationApproved", userId, actor,
                        Map.of("companyName", seller.getCompanyName()));
                return SellerApplicationResponse.from(seller);
            });
        } catch (RuntimeException failure) {
            Runnable compensation = undo.get();
            if (compensation != null) {
                try {
                    compensation.run();
                } catch (RuntimeException cleanup) {
                    if (cleanup != failure) failure.addSuppressed(cleanup);
                    log.error("Seller role compensation failed for {}; reconciliation is required", userId);
                }
            }
            throw failure;
        }
    }

    @Transactional
    public SellerApplicationResponse reject(UUID userId, String reason, UUID actor) {
        Seller seller = pending(userId);
        seller.setStatus(SellerStatus.REJECTED);
        seller.setRejectionReason(reason);
        seller.setReviewedBy(actor);
        seller.setReviewedAt(OffsetDateTime.now(clock));
        sellers.saveAndFlush(seller);
        events.publish("SellerApplicationRejected", userId, actor,
                Map.of("companyName", seller.getCompanyName(), "reason", reason));
        return SellerApplicationResponse.from(seller);
    }

    private Seller pending(UUID userId) {
        lockUser(userId);
        Seller seller = sellers.findById(userId)
                .orElseThrow(() -> new DomainException(UserErrorCode.SELLER_APPLICATION_NOT_FOUND));
        if (seller.getStatus() != SellerStatus.PENDING)
            throw new DomainException(UserErrorCode.SELLER_APPLICATION_NOT_PENDING);
        return seller;
    }

    private User lockUser(UUID userId) {
        return users.findForProfileUpdate(userId)
                .orElseThrow(() -> new DomainException(UserErrorCode.USER_NOT_FOUND));
    }

    private static UUID subject(CurrentUser currentUser) {
        if (currentUser == null || currentUser.id() == null) throw new DomainException(CommonErrorCode.UNAUTHENTICATED);
        return currentUser.id();
    }

    private static List<String> names(List<KeycloakAdminClient.RealmRole> roles) {
        return roles.stream().map(KeycloakAdminClient.RealmRole::name).distinct().sorted().toList();
    }
}
