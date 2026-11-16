package com.group5.paymentservice.sellerbank;

import com.group5.common_core.constant.CommonErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.common_core.model.PageInfo;
import com.group5.common_security.CurrentUser;
import com.group5.paymentservice.shared.exception.PaymentErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BankAccountService {
    private final BankAccountRepository accounts;
    private final BankAccountCipher cipher;
    private final ApplicationEventPublisher events;

    @Transactional
    public BankAccountView create(CurrentUser user, BankAccountRequest request) {
        requireRole(user, "SELLER");
        validate(request);
        accounts.lockSeller(user.id());
        UUID id = UUID.randomUUID();
        boolean active = !Boolean.FALSE.equals(request.active());
        boolean isDefault = Boolean.TRUE.equals(request.isDefault());
        if (isDefault && !active) throw invalid();
        String encrypted = cipher.encrypt(request.accountNumber());
        if (isDefault) accounts.clearDefault(user.id(), id, user.id());
        accounts.insert(id, user.id(), request, encrypted, isDefault, active);
        return accounts.find(id).orElseThrow();
    }

    @Transactional
    public BankAccountView update(CurrentUser user, UUID id, BankAccountRequest request) {
        requireRole(user, "SELLER");
        accounts.lockSeller(user.id());
        BankAccountView account = owned(user, id);
        validate(request);
        boolean active = request.active() == null ? account.active() : request.active();
        boolean isDefault = request.isDefault() == null ? account.isDefault() && active : request.isDefault();
        if (isDefault && !active) throw invalid();
        String oldCiphertext = accounts.encryptedNumber(id);
        boolean changed = !account.bankCode().equals(request.bankCode())
                || !account.accountHolderName().equals(request.accountHolderName().trim())
                || !cipher.decrypt(oldCiphertext).equals(request.accountNumber());
        String encrypted = changed ? cipher.encrypt(request.accountNumber()) : oldCiphertext;
        if (isDefault) accounts.clearDefault(user.id(), id, user.id());
        // Sửa ngân hàng, số tài khoản hoặc tên chủ tài khoản thì phải xóa kết quả xác minh cũ.
        accounts.update(id, user.id(), request, encrypted, changed, isDefault, active);
        BankAccountView updated = accounts.find(id).orElseThrow();
        releaseIfReady(updated);
        return updated;
    }

    @Transactional(readOnly = true)
    public BankAccountPage list(CurrentUser user, int page, int size) {
        requireRole(user, "SELLER");
        if (page < 0 || size < 1 || size > 100) throw invalid();
        long count = accounts.count(user.id());
        return new BankAccountPage(accounts.list(user.id(), page, size),
                new PageInfo(page, size, count, (int) ((count + size - 1) / size)));
    }

    @Transactional
    public BankAccountView setDefault(CurrentUser user, UUID id) {
        requireRole(user, "SELLER");
        accounts.lockSeller(user.id());
        BankAccountView account = owned(user, id);
        if (!account.active()) throw invalid();
        if (!account.isDefault()) {
            accounts.clearDefault(user.id(), id, user.id());
            accounts.setDefault(id, user.id());
        }
        BankAccountView updated = accounts.find(id).orElseThrow();
        releaseIfReady(updated);
        return updated;
    }

    @Transactional
    public BankAccountView verify(CurrentUser user, UUID id, BankVerificationRequest request) {
        requireRole(user, "ADMIN");
        if (request == null || request.expectedVersion() == null || request.expectedVersion() < 0
                || !("VERIFIED".equals(request.verificationStatus()) || "REJECTED".equals(request.verificationStatus())))
            throw invalid();
        BankAccountView before = accounts.find(id).orElseThrow(BankAccountService::notFound);
        accounts.lockSeller(before.sellerId());
        BankAccountView account = accounts.find(id).orElseThrow(BankAccountService::notFound);
        // Khóa tuần tự hóa ghi; version bảo đảm admin đang duyệt đúng dữ liệu đã xem.
        if (account.version() != request.expectedVersion()) throw conflict();
        if (!account.active()) throw invalid();
        String reason = request.reason() == null ? null : request.reason().trim();
        if ("REJECTED".equals(request.verificationStatus()) && (reason == null || reason.isEmpty() || reason.length() > 1000))
            throw invalid();
        int updatedRows = accounts.verify(id, user.id(), request.verificationStatus(),
                "REJECTED".equals(request.verificationStatus()) ? reason : null, request.expectedVersion());
        if (updatedRows != 1) throw conflict();
        BankAccountView updated = accounts.find(id).orElseThrow();
        releaseIfReady(updated);
        return updated;
    }

    @Transactional(readOnly = true)
    public Optional<VerifiedBankAccount> findVerifiedDefault(UUID sellerId) { return accounts.verifiedDefault(sellerId); }

    private BankAccountView owned(CurrentUser user, UUID id) {
        return accounts.find(id).filter(account -> account.sellerId().equals(user.id())).orElseThrow(BankAccountService::notFound);
    }

    private void releaseIfReady(BankAccountView account) {
        // Chỉ tài khoản mặc định, đang hoạt động và đã xác minh mới giải phóng payout đang giữ.
        if (account.active() && account.isDefault() && "VERIFIED".equals(account.verificationStatus()))
            events.publishEvent(new BankAccountVerified(account.sellerId()));
    }

    private static void validate(BankAccountRequest request) {
        if (request == null || request.bankCode() == null || !request.bankCode().matches("[0-9]{6}")
                || request.accountNumber() == null || !request.accountNumber().matches("[0-9]{4,34}")
                || request.accountHolderName() == null || request.accountHolderName().trim().isEmpty()
                || request.accountHolderName().trim().length() > 100
                || request.accountHolderName().codePoints().anyMatch(Character::isISOControl)) throw invalid();
    }

    private static void requireRole(CurrentUser user, String role) {
        if (user == null || user.id() == null) throw new DomainException(PaymentErrorCode.UNAUTHENTICATED);
        if (!user.hasRole(role)) throw new DomainException(CommonErrorCode.FORBIDDEN);
    }

    private static DomainException invalid() { return new DomainException(PaymentErrorCode.SELLER_BANK_ACCOUNT_INVALID); }
    private static DomainException conflict() { return new DomainException(PaymentErrorCode.SELLER_BANK_ACCOUNT_CONFLICT); }
    private static DomainException notFound() { return new DomainException(PaymentErrorCode.SELLER_BANK_ACCOUNT_NOT_FOUND); }
    public record BankAccountPage(List<BankAccountView> content, PageInfo page) { }
}
