package com.group5.commerceservice.cart;


import com.group5.commerceservice.cart.repository.CartRepository;
import com.group5.commerceservice.shared.config.CartProperties;
import com.group5.commerceservice.shared.exception.CommerceErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUserAuditor;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class CartExpiryJob {
    private final CartRepository cartRepository;
    private final CartProperties cartProperties;
    // Chạy mỗi giờ (cấu hình qua nexus.cart.expiry-cron).
    // ShedLock đảm bảo chỉ một instance chạy job tại một thời điểm.
    @Scheduled(cron = "${nexus.cart.expiry-cron:0 0 * * * *}")
    @SchedulerLock(
            name = "cartExpiryJob",
            lockAtMostFor = "PT5M"
    )
    @Transactional
    public void expireInactiveCarts() {
        int expirationDays = cartProperties.getExpirationDays();
        if (expirationDays < 1) {
            throw new DomainException(CommerceErrorCode.INVALID_CONFIGURATION);
        }
        int expiredCount = cartRepository.expireInactiveActiveCarts(expirationDays, CurrentUserAuditor.SYSTEM_ID);
        log.info(
                "Cart expiry job completed. expirationDays={}, expiredCount={}",
                expirationDays,
                expiredCount
        );
    }
}
