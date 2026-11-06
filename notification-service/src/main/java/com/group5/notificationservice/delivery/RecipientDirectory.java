package com.group5.notificationservice.delivery;

import java.util.UUID;

// LẤY EMAIL NGƯỜI NHẬN THEO userId. HIỆN TẠI HỎI KEYCLOAK, SAU NÀY CÓ THỂ ĐỔI SANG BẢN SAO LOCAL TỪ EVENT UserUpdated
public interface RecipientDirectory {

    String emailOf(UUID userId);
}
