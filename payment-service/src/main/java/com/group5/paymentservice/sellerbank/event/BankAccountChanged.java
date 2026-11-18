package com.group5.paymentservice.sellerbank.event;

import java.util.UUID;

/**
 * Local event; manual rechecks run after commit to avoid seller-bank -> payment-group lock
 * inversion.
 */
public record BankAccountChanged(UUID sellerId) {}
