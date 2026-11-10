package com.group5.inventoryservice.shared.web;

import com.group5.common_core.model.ApiResponse;
import com.group5.common_core.model.PageInfo;
import org.springframework.data.domain.Page;

import java.util.List;

public final class Pages {

    public static final int MAX_SIZE = 100;

    private Pages() {
    }

    public static <T> ApiResponse<List<T>> response(Page<T> page) {
        return ApiResponse.page(page.getContent(), new PageInfo(page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages()));
    }
}
