package com.group5.commerceservice.cart.dto.response;

import java.util.List;

public record CartValidationResponse(boolean valid, List<CartIssue> issues) { }
