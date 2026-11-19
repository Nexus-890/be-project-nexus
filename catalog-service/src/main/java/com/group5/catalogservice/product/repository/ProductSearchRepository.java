package com.group5.catalogservice.product.repository;

import com.group5.catalogservice.product.dto.response.ProductResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface ProductSearchRepository {
    Page<ProductResponse> searchFullText(UUID categoryId, String keyword, String sort, Boolean auctionEnabled,
                                         BigDecimal minPrice, BigDecimal maxPrice, Pageable pageable);

    Page<ProductResponse> searchVisibleProducts(UUID categoryId, String keyword, String sort,
                                               Boolean auctionEnabled, BigDecimal maxPrice, Pageable pageable);

    List<ProductResponse> discover(boolean popular, int limit);
}
