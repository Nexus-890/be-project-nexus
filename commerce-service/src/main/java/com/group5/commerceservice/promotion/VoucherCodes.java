package com.group5.commerceservice.promotion;

import com.group5.common_core.exception.DomainException;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static com.group5.commerceservice.shared.exception.CommerceErrorCode.VOUCHER_INVALID_REQUEST;

public final class VoucherCodes {
    private static final Pattern CODE = Pattern.compile("[A-Z0-9][A-Z0-9_-]{0,49}");

    private VoucherCodes() { }

    /** Null means the client did not supply a selection. An empty array explicitly clears it. */
    public static List<String> requested(List<String> codes, String alias) {
        if (codes == null) {
            if (alias == null || alias.isBlank()) return null;
            codes = List.of(alias);
        }
        if (codes.size() > 50) throw new DomainException(VOUCHER_INVALID_REQUEST, "At most 50 voucher codes are allowed");
        return codes.stream().map(VoucherCodes::normalize).toList();
    }

    public static List<String> withCartFallback(List<String> requested, String cartCode) {
        if (requested != null) return requested;
        if (cartCode == null || cartCode.isBlank()) return List.of();
        return requested(null, cartCode);
    }

    private static String normalize(String code) {
        String value = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(value).matches()) throw new DomainException(VOUCHER_INVALID_REQUEST, "Invalid voucher code");
        return value;
    }
}
