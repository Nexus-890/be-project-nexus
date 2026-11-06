package com.group5.notificationservice.health;

public enum HealthStatus {
    UP,
    DOWN,
    // CHỈ DÙNG CHO BROKER: JOB CHƯA CHẠY LẦN NÀO HOẶC KHÔNG ĐỌC ĐƯỢC REDIS
    UNKNOWN
}
