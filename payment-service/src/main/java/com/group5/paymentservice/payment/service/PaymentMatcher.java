package com.group5.paymentservice.payment.service;

import com.group5.paymentservice.payment.dto.request.SepayWebhookRequest;
import com.group5.paymentservice.payment.entity.PaymentSession;
import com.group5.paymentservice.payment.repository.PaymentSessionRepository;
import com.group5.paymentservice.shared.config.SepayProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
public class PaymentMatcher {
    private static final String MEMO_PREFIX = "NX";
    private static final int MEMO_MAX_LENGTH = 27;
    private final PaymentSessionRepository sessions;
    private final String contentPrefix;

    public PaymentMatcher(PaymentSessionRepository sessions, SepayProperties properties) {
        this.sessions = sessions;
        this.contentPrefix = normalize(properties.transferContentPrefix());
    }


    public String memoOf(SepayWebhookRequest request) {
        /*
        {
          "code": " nx20261105001 ",
          "content": "SEVQR NX20261105001"
        }
        nên là có thể dùng content thay code, nếu code k được truyền xuống
        */
        String code = normalize(request.code());
        if (!code.isEmpty()) return code;
        return normalize(request.content());
    }

    @Transactional(readOnly = true)
    public Optional<PaymentSession> match(SepayWebhookRequest request) {
        //Ưu tiên code, nếu code empty thì lấy content
        String memo = memoOf(request);
        Optional<PaymentSession> exact = match(memo);
        //kiểm tra xem exact hiện đã correct chưa -> nếu có thì return luôn, chưa thì tiếp tục tìm bằng cách bỏ...
        if (exact.isPresent() || !normalize(request.code()).isEmpty()) return exact;
        // bỏ phần contentPrefix ( cấu hình trong properties )
        Set<String> candidates = new LinkedHashSet<>();
        if (!contentPrefix.isEmpty() && memo.startsWith(contentPrefix)) {
            candidates.add(memo.substring(contentPrefix.length()));
        }
        candidates.addAll(memoCandidatesIn(request.content()));
        candidates.remove(memo);
        for (String candidate : candidates) {
            Optional<PaymentSession> found = match(candidate);
            if (found.isPresent()) return found;
        }
        return Optional.empty();
    }

    private static Set<String> memoCandidatesIn(String content) {
        Set<String> candidates = new LinkedHashSet<>();
        if (content == null) return candidates;
        for (String token : content.toUpperCase(Locale.ROOT).split("[^A-Z0-9]+")) {
            for (int i = token.indexOf(MEMO_PREFIX); i >= 0; i = token.indexOf(MEMO_PREFIX, i + 1)) {
                candidates.add(token.substring(i, Math.min(token.length(), i + MEMO_MAX_LENGTH)));
            }
        }
        return candidates;
    }

    //
    @Transactional(readOnly = true)
    public Optional<PaymentSession> match(String memo) {
        String normalized = normalize(memo);
        if (!normalized.matches("[A-Z0-9]{1,32}")) return Optional.empty();
        return sessions.findByMemoCode(normalized);
    }

    //hàm này xóa space với biến in hoa result
    private static String normalize(String memo) {
        //strip ( xóa khoảng trắng - hiện đại ngon hơn trim )
        return memo == null ? "" : memo.strip().toUpperCase(Locale.ROOT).replaceAll("(?U)\\s+", "");
    }
}
