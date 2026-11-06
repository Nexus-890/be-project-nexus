package com.group5.paymentservice.payment.job;

import com.group5.paymentservice.payment.repository.PaymentPendingOrderCancellationRepository;
import com.group5.paymentservice.payment.service.RefundService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class PendingOrderCancellationJob {
    private static final Logger log = LoggerFactory.getLogger(PendingOrderCancellationJob.class);

    private final PaymentPendingOrderCancellationRepository pendingOrders;
    private final RefundService refunds;
    private final int batchSize;

    public PendingOrderCancellationJob(PaymentPendingOrderCancellationRepository pendingOrders, RefundService refunds,
                                       @Value("${nexus.payment.pending-cancellations.batch-size:100}") int batchSize) {
        this.pendingOrders = pendingOrders;
        this.refunds = refunds;
        this.batchSize = batchSize;
    }

    // CHỈ LẤY NHÓM CÓ DÒNG PENDING MÀ INSTRUCTION ĐÃ CÓ BẰNG CHỨNG FUNDING -> KHÔNG QUÉT LẠI DÒNG CHƯA CÓ TIỀN
    // MỖI NHÓM 1 TRANSACTION RIÊNG (QUA PROXY) -> 1 NHÓM LỖI KHÔNG CHẶN NHÓM KHÁC
    // NHIỀU INSTANCE CHẠY CÙNG LÚC VẪN AN TOÀN: KHÓA payment-group: + KHÓA DÒNG PENDING TRONG applyPendingCancellations
    @Scheduled(cron = "${nexus.payment.pending-cancellations.cron:*/30 * * * * *}")
    public void applyFundedCancellations() {
        for (UUID groupId : pendingOrders.findFundedGroupIds(batchSize)) {
            try {
                refunds.applyPendingCancellations(groupId);
            } catch (RuntimeException error) {
                log.warn("Pending order cancellations could not be applied: orderGroupId={}", groupId, error);
            }
        }
    }
}
