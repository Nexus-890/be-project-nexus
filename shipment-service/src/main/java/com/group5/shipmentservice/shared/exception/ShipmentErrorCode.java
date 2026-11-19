package com.group5.shipmentservice.shared.exception;

import com.group5.common_core.constant.ErrorCode;
import org.springframework.http.HttpStatus;

public enum ShipmentErrorCode implements ErrorCode {
    INVALID_SIGNATURE(HttpStatus.UNAUTHORIZED, "Chữ ký webhook không hợp lệ"),
    INVALID_WEBHOOK(HttpStatus.BAD_REQUEST, "Nội dung webhook không hợp lệ"),
    CARRIER_CANCELLATION_REJECTED(HttpStatus.CONFLICT, "Nhà vận chuyển từ chối hủy vận đơn"),
    INVALID_TRANSITION(HttpStatus.CONFLICT, "Không thể chuyển sang trạng thái vận chuyển được yêu cầu"),
    UNSUPPORTED_CARRIER(HttpStatus.BAD_REQUEST, "Nhà vận chuyển không được hỗ trợ"),
    INVALID_CARRIER_STATUS(HttpStatus.UNPROCESSABLE_ENTITY, "Trạng thái nhà vận chuyển không được hỗ trợ"),
    IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "Cần có Idempotency-Key"),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "Khóa hoặc yêu cầu tạo vận đơn đã được dùng với nội dung khác"),
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy đơn hàng để tạo vận đơn"),
    ORDER_NOT_PAID(HttpStatus.CONFLICT, "Đơn hàng chưa thanh toán hoặc không còn ở trạng thái PAID"),
    INVALID_ORDER_SNAPSHOT(HttpStatus.BAD_GATEWAY, "Snapshot vận chuyển của đơn hàng không hợp lệ"),
    COMMERCE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Dịch vụ đơn hàng hiện không khả dụng"),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Dữ liệu báo giá không hợp lệ"),
    INVALID_PERIOD(HttpStatus.BAD_REQUEST, "Khoảng thời gian thống kê phải có from trước to và dài tối đa 366 ngày"),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Dữ liệu yêu cầu không hợp lệ"),
    INVALID_REQUEST_BODY(HttpStatus.BAD_REQUEST, "Nội dung yêu cầu không hợp lệ"),
    INVALID_ADDRESS(HttpStatus.BAD_REQUEST, "Mã địa giới không tồn tại hoặc không đúng quan hệ cha con"),
    WAREHOUSE_NOT_FOUND(HttpStatus.NOT_FOUND, "Kho chưa được cấu hình địa chỉ lấy hàng"),
    SKU_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy SKU đang bán"),
    NO_SERVICE(HttpStatus.UNPROCESSABLE_ENTITY, "Không có dịch vụ vận chuyển phù hợp với tuyến này"),
    CARRIER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Nhà vận chuyển không phản hồi trong thời gian cho phép"),
    CIRCUIT_OPEN(HttpStatus.SERVICE_UNAVAILABLE, "Kết nối nhà vận chuyển đang tạm ngắt sau nhiều lần lỗi"),
    CARRIER_CONFIGURATION_ERROR(HttpStatus.SERVICE_UNAVAILABLE, "Cấu hình nhà vận chuyển chưa đầy đủ hoặc không hợp lệ"),
    CATALOG_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Dịch vụ sản phẩm hiện không khả dụng"),
    INVALID_CATALOG_RESPONSE(HttpStatus.BAD_GATEWAY, "Dữ liệu giá sản phẩm không hợp lệ"),
    INVALID_CARRIER_RESPONSE(HttpStatus.BAD_GATEWAY, "Dữ liệu phản hồi của nhà vận chuyển không hợp lệ"),
    INVALID_PARAMETER(HttpStatus.BAD_REQUEST, "Tham số yêu cầu không hợp lệ"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Cần có token truy cập hợp lệ"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Bạn không có quyền thực hiện thao tác này"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy tài nguyên được yêu cầu"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Phương thức HTTP không được hỗ trợ"),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "Định dạng phản hồi không được hỗ trợ"),
    CONFLICT(HttpStatus.CONFLICT, "Yêu cầu xung đột với trạng thái hiện tại"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Định dạng nội dung yêu cầu không được hỗ trợ"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Có quá nhiều yêu cầu, vui lòng thử lại sau"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Dịch vụ cần thiết hiện không khả dụng"),
    GATEWAY_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "Dịch vụ không phản hồi trong thời gian cho phép"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Đã xảy ra lỗi xử lý tại dịch vụ vận chuyển");

    private final HttpStatus status;
    private final String message;

    ShipmentErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override
    public String code() {
        return "SHIPMENT_" + name();
    }

    @Override
    public HttpStatus status() {
        return status;
    }

    @Override
    public String message() {
        return message;
    }

    public static ShipmentErrorCode forStatus(int status) {
        return switch (status) {
            case 400 -> INVALID_PARAMETER;
            case 401 -> UNAUTHENTICATED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 409 -> CONFLICT;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 422 -> NO_SERVICE;
            case 429 -> RATE_LIMITED;
            case 503 -> SERVICE_UNAVAILABLE;
            case 504 -> GATEWAY_TIMEOUT;
            default -> INTERNAL_ERROR;
        };
    }
}
