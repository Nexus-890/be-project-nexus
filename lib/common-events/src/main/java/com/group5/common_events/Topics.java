package com.group5.common_events;

public final class Topics {

    public static final String USER = "nexus.user.events";
    public static final String CATALOG = "nexus.catalog.events";
    public static final String COMMERCE = "nexus.commerce.events";
    /** Lệnh đặt giá, phân vùng theo auctionId để xử lý tuần tự từng phiên. */
    public static final String AUCTION_BIDS = "nexus.auction.bids";
    public static final String AUCTION = "nexus.auction.events";
    public static final String INVENTORY = "nexus.inventory.events";
    public static final String PAYMENT = "nexus.payment.events";
    public static final String SHIPMENT = "nexus.shipment.events";
    public static final String RATING = "nexus.rating.events";

    private Topics() {
    }
}