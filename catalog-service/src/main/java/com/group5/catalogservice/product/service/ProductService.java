package com.group5.catalogservice.product.service;

import com.group5.catalogservice.product.dto.response.ProductResponse;
import com.group5.catalogservice.product.entity.Product;
import com.group5.catalogservice.product.repository.ProductRepository;
import com.group5.common_core.constant.ErrorCode;
import com.group5.common_core.exception.DomainException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;

    @Transactional
    public Page<ProductResponse> getProductsByCategory(
            UUID categoryId, int page, int size) {

        validatePagination(page, size);

        Pageable pageable = PageRequest.of(page, size);

        Page<Product> products;

        if (categoryId == null) {
            products = productRepository.findByStatusAndDeletedAtIsNull(
                    "ACTIVE", pageable);
        } else {
            products = productRepository.findByCategory_IdAndStatusAndDeletedAtIsNull(
                    categoryId, "ACTIVE", pageable);
        }

        return products.map(this::toResponse);
    }


    @Transactional
    public Page<ProductResponse> getMyProducts(UUID sellerId, int page, int size) {
        if (sellerId == null) {
            throw new DomainException(ErrorCode.UNAUTHENTICATED);
        }

        validatePagination(page, size);

        log.debug("Getting seller products: sellerId={}, page={}, size={}", sellerId, page, size);

        Pageable pageable = PageRequest.of(page, size);

        Page<ProductResponse> responses = productRepository
                .findBySellerIdAndDeletedAtIsNull(sellerId, pageable).map(this::toResponse);

        log.debug("Finished getting seller products: sellerId={}, totalElements={}",
                sellerId, responses.getTotalElements());

        return responses;
    }

    private void validatePagination(int page, int size) {
        if (page < 0 || size < 1) {
            throw new DomainException(ErrorCode.DATA_INVALID);
        }
    }

    //product thường
    private ProductResponse toResponse(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getName(),
                product.getSlug(),
                product.getStatus(),
                product.getCategory().getId(),
                product.getSellerId()
        );
    }
}
