package com.group5.userservice.phone;

public interface SmsSender {

    void send(String phone, String text);
}
