package com.group5.auctionservice;

import com.group5.auctionservice.auction.AuctionInventoryClient;
import com.group5.auctionservice.auction.CatalogClient;
import com.group5.auctionservice.reputation.RatingEligibilityHttpClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;


@SpringBootApplication
@EnableFeignClients(clients = {CatalogClient.class, AuctionInventoryClient.class, RatingEligibilityHttpClient.class})
public class AuctionServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(AuctionServiceApplication.class, args);
	}

}
