package com.group5.paymentservice.payout.dto.response;

import com.group5.common_core.model.PageInfo;

import java.util.List;

public record PayoutPage(List<PayoutView> content, PageInfo page) { }
