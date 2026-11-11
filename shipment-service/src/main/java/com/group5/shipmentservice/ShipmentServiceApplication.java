package com.group5.shipmentservice;

import com.group5.shipmentservice.shipping.client.CatalogClient;
import com.group5.shipmentservice.shipping.client.CommerceOrderClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients(clients = {CatalogClient.class, CommerceOrderClient.class})
public class ShipmentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShipmentServiceApplication.class, args);
    }
}
