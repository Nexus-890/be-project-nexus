package com.group5.catalogservice.product;

import com.group5.common_core.constant.ErrorCode;
import com.group5.common_core.exception.DomainException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.validator.constraints.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;

    @Transactional
    public Page<ProductResponse> getProductsByCategory(
            UUID categoryId, int page, int size) {

        validatePagination(page, size);

        log.debug("Getting active products: categoryId={}, page={}, size={}", categoryId, page, size);

        Pageable pageable = PageRequest.of(page, size);

        Page<ProductResponse> result = productRepository.findByCategory_IdAndStatusAndDeletedAtIsNull
                        (categoryId, "ACTIVE", pageable).map(ProductResponse::new);

        log.debug("Finished getting active products: categoryId={}, totalElements={}",
                categoryId, result.getTotalElements());

        return result;
    }

    @Transactional
    public Page<ProductResponse> getMyProducts(
            UUID sellerId, int page, int size) {

        if (sellerId == null) {
            throw new DomainException(ErrorCode.UNAUTHENTICATED);
        }

        validatePagination(page, size);

        log.debug(
                "Getting seller products: sellerId={}, page={}, size={}",
                sellerId, page, size
        );

        Pageable pageable = PageRequest.of(page, size);

        Page<ProductResponse> result = productRepository
                .findBySellerIdAndDeletedAtIsNull(sellerId, pageable)
                .map(ProductResponse::new);

        log.debug(
                "Finished getting seller products: sellerId={}, totalElements={}",
                sellerId, result.getTotalElements());

        return result;
    }

    private void validatePagination(int page, int size) {
        if (page < 0 || size < 1) {
            throw new DomainException(ErrorCode.DATA_INVALID);
        }
    }
}
