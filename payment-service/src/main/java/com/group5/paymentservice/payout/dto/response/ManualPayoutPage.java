package com.group5.paymentservice.payout.dto.response;

import com.group5.common_core.model.PageInfo;

import java.util.List;

/**
 * Internal query result; the controller exposes content and page in the shared top-level envelope.
 */
public record ManualPayoutPage(List<ManualPayoutView> content, PageInfo page) {}
