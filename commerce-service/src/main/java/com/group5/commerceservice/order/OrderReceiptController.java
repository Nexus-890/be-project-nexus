package com.group5.commerceservice.order;

import com.group5.common_core.model.ProtocolResponse;
import com.group5.common_security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class OrderReceiptController {
    private final OrderReceiptService receipts;

    @GetMapping("/api/v1/orders/{id}/invoice")
    @ProtocolResponse("ORD-12 returns an HTML receipt; errors retain Problem Details")
    @PreAuthorize("hasAuthority('ORDER.VIEW')")
    public ResponseEntity<String> getInvoice(CurrentUser user, @PathVariable UUID id) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .body(receipts.invoice(user, id));
    }
}
