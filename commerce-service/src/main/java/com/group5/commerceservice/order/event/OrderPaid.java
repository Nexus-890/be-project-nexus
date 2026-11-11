package com.group5.commerceservice.order.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.group5.commerceservice.order.OrderType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Thông báo một đơn đã thanh toán, mang phần tiền của đơn và snapshot vận chuyển để các dịch vụ dùng tiếp. */
public record OrderPaid(
        @NotNull UUID orderId, @NotNull UUID buyerId, @NotBlank @Size(max = 40) String orderNumber,
        @NotNull OrderType orderType, @JsonInclude(JsonInclude.Include.ALWAYS) UUID referenceId,
        @NotNull @Pattern(regexp = ".*Z$") String paidAt, @NotNull UUID orderGroupId,
        @NotNull UUID sellerId, @PositiveOrZero long orderVersion, @NotNull UUID sessionId,
        @NotNull UUID instructionId, @NotNull UUID transactionId,
        @NotNull @Pattern(regexp = "\\d+\\.\\d{2}") String paidAmount,
        @NotNull @Pattern(regexp = "VND") String currency, @NotNull UUID warehouseId,
        @NotNull @Pattern(regexp = "\\d+\\.\\d{2}") String shippingFee,
        @NotNull @Valid ShippingInfo shippingInfo) {

    /** Hàng hóa và người nhận đã chốt; parcel/pickupAddress có thể null khi snapshot chưa cung cấp. */
    public record ShippingInfo(@NotNull @Valid Recipient recipient,
                               @NotEmpty List<@NotNull @Valid Item> items,
                               @JsonInclude(JsonInclude.Include.ALWAYS) @Valid Parcel parcel,
                               @JsonInclude(JsonInclude.Include.ALWAYS) @Valid Address pickupAddress) { }

    /** Thông tin người nhận giữ nguyên từ shipping_info của đơn. */
    public record Recipient(@NotBlank @Size(max = 150) String name,
                            @NotBlank @Size(max = 20) String phone, @NotNull @Valid Address address) { }

    /** Giữ cả mã phường, quận và tỉnh để dịch vụ vận chuyển không phải suy đoán từ địa chỉ chữ. */
    public record Address(@NotBlank @Size(max = 500) String line1,
                          @NotBlank @Size(max = 40) String wardCode,
                          @NotBlank @Size(max = 40) String districtCode,
                          @NotBlank @Size(max = 40) String provinceCode) { }

    /** Dòng hàng lấy từ order_items, không thay bằng tên hoặc số lượng mới từ Catalog. */
    public record Item(@NotNull UUID skuId, @NotBlank @Size(max = 255) String productName,
                       @Min(1) int quantity) { }

    /** Số đo kiện hàng chỉ hợp lệ khi snapshot có đủ cân nặng và kích thước dương. */
    public record Parcel(@NotNull @Min(1) Integer weightGrams,
                         @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal lengthCm,
                         @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal widthCm,
                         @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal heightCm) { }
}
