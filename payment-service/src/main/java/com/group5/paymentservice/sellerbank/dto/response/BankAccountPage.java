package com.group5.paymentservice.sellerbank.dto.response;

import com.group5.common_core.model.PageInfo;

import java.util.List;

public record BankAccountPage(List<BankAccountView> content, PageInfo page) { }
