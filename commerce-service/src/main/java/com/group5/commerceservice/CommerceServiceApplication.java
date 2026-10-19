package com.group5.commerceservice;

import com.group5.common_web.exception.GlobalExceptionHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Import;


@SpringBootApplication
@ConfigurationPropertiesScan
@EnableFeignClients(basePackages = "com.group5.commerceservice.cart.dto")
@Import(GlobalExceptionHandler.class)
public class CommerceServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(CommerceServiceApplication.class, args);
	}

}
