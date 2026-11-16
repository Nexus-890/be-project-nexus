package com.group5.userservice.seller;

import com.group5.common_core.constant.CommonErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import com.group5.common_security.CurrentUserAuditor;
import com.group5.userservice.iam.UserRepository;
import com.group5.userservice.iam.entity.User;
import com.group5.userservice.shared.exception.UserErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
public class SellerService {
    private final UserRepository users;
    private final SellerRepository sellers;
    private final Clock clock;

    @Transactional
    public SellerProfileResponse getMe(CurrentUser currentUser) {
        return SellerProfileResponse.from(ownProfile(currentUser));
    }

    @Transactional
    public SellerProfileResponse updateMe(CurrentUser currentUser, SellerProfileRequest request) {
        Seller seller = ownProfile(currentUser);
        seller.setCompanyName(request.companyName());
        seller.setContactPhone(request.contactPhone());
        return SellerProfileResponse.from(sellers.saveAndFlush(seller));
    }

    private Seller ownProfile(CurrentUser currentUser) {
        if (currentUser == null || currentUser.id() == null) throw new DomainException(CommonErrorCode.UNAUTHENTICATED);
        if (!currentUser.hasRole("SELLER")) throw new DomainException(CommonErrorCode.FORBIDDEN);
        // Khóa user để hai lần GET đầu tiên không tạo trùng hồ sơ và không xung đột luồng đăng ký ở #210.
        User user = users.findForProfileUpdate(currentUser.id())
                .orElseThrow(() -> new DomainException(UserErrorCode.USER_NOT_FOUND));
        return sellers.findById(user.getId()).orElseGet(() -> bootstrap(user));
    }

    private Seller bootstrap(User user) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Seller seller = new Seller();
        seller.setUserId(user.getId());
        seller.setCompanyName(user.getFullName());
        seller.setContactPhone(user.getPhone());
        seller.setStatus(SellerStatus.ACTIVE);
        seller.setAppliedAt(now);
        // User đã có quyền SELLER được khởi tạo hồ sơ ACTIVE với system là người ghi nhận.
        seller.setReviewedBy(CurrentUserAuditor.SYSTEM_ID);
        seller.setReviewedAt(now);
        return sellers.saveAndFlush(seller);
    }
}
