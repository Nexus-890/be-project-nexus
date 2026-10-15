package com.group5.catalogservice.product.service;

import com.group5.catalogservice.product.dto.request.SkuRequest;
import com.group5.catalogservice.product.dto.response.SkuResponse;
import com.group5.catalogservice.product.entity.Product;
import com.group5.catalogservice.product.entity.Skus;
import com.group5.catalogservice.product.repository.ProductRepository;
import com.group5.catalogservice.product.repository.SkuRepository;
import com.group5.common_core.constant.ErrorCode;
import com.group5.common_core.exception.DomainException;
import com.group5.common_security.CurrentUser;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

@Slf4j
@Service
public class SkuService {
    private final SkuRepository skuRepository;
    private final ProductRepository productRepository;

    public SkuService(
            SkuRepository skuRepository,
            ProductRepository productRepository
    ) {
        this.skuRepository = skuRepository;
        this.productRepository = productRepository;
    }

    @Transactional
    public SkuResponse create(
            UUID productId,
            SkuRequest request,
            CurrentUser currentUser
    ) {
        requirePrivilege(currentUser, "PRODUCT.UPDATE");

        Product product = findEditableProduct(productId, currentUser);

        validatePrice(request.getPrice());

        Skus sku = new Skus();
        sku.setProduct(product);
        sku.setSkuCode(request.getSkuCode().trim());
        sku.setColor(request.getColor().trim());
        sku.setSize(request.getSize().trim());
        sku.setPrice(request.getPrice());

        try {
            Skus saved = skuRepository.saveAndFlush(sku);

            log.info(
                    "Created SKU: id={}, productId={}, actorId={}",
                    saved.getId(),
                    productId,
                    currentUser.id()
            );

            return toResponse(saved);

        } catch (DataIntegrityViolationException exception) {
            if (isDuplicateConstraint(exception)) {
                throw new DomainException(ErrorCode.SKU_DUPLICATE);
            }

            throw exception;
        }
    }

    @Transactional
    public SkuResponse update(
            UUID id,
            SkuRequest request,
            CurrentUser currentUser
    ) {
        requirePrivilege(currentUser, "PRODUCT.UPDATE");

        Skus sku = skuRepository.findById(id)
                .orElseThrow(() ->
                        new DomainException(ErrorCode.SKU_NOT_FOUND));

        Product product = findEditableProduct(
                sku.getProduct().getId(),
                currentUser
        );

        validatePrice(request.getPrice());

        sku.setSkuCode(request.getSkuCode().trim());
        sku.setColor(request.getColor().trim());
        sku.setSize(request.getSize().trim());
        sku.setPrice(request.getPrice());

        try {
            Skus saved = skuRepository.saveAndFlush(sku);

            log.info(
                    "Updated SKU: id={}, productId={}, actorId={}",
                    saved.getId(),
                    product.getId(),
                    currentUser.id()
            );

            return toResponse(saved);

        } catch (DataIntegrityViolationException exception) {
            if (isDuplicateConstraint(exception)) {
                throw new DomainException(ErrorCode.SKU_DUPLICATE);
            }

            throw exception;
        }
    }

    @Transactional
    public SkuResponse getById(UUID id) {
        Skus sku = skuRepository.findById(id)
                .orElseThrow(() ->
                        new DomainException(ErrorCode.SKU_NOT_FOUND));

        return toResponse(sku);
    }

    private Product findEditableProduct(
            UUID productId,
            CurrentUser currentUser
    ) {
        Product product = productRepository
                .findByIdAndDeletedAtIsNull(productId)
                .orElseThrow(() ->
                        new DomainException(ErrorCode.PRODUCT_NOT_FOUND));

        if (!currentUser.hasRole("ADMIN")
                && !product.getSellerId().equals(currentUser.id())) {
            throw new DomainException(ErrorCode.UNAUTHORIZED);
        }

        return product;
    }

    private void requirePrivilege(
            CurrentUser currentUser,
            String privilege
    ) {
        if (currentUser == null) {
            throw new DomainException(ErrorCode.UNAUTHENTICATED);
        }

        if (!currentUser.hasPrivilege(privilege)) {
            throw new DomainException(ErrorCode.UNAUTHORIZED);
        }
    }

    private void validatePrice(BigDecimal price) {
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new DomainException(ErrorCode.DATA_INVALID);
        }
    }

    private boolean isDuplicateConstraint(
            DataIntegrityViolationException exception
    ) {
        Throwable cause = exception;

        while (cause != null) {
            String message = cause.getMessage();

            if (message != null
                    && (message.contains("idx_skus_variant")
                    || message.contains("sku_code"))) {
                return true;
            }

            cause = cause.getCause();
        }

        return false;
    }

    private SkuResponse toResponse(Skus sku) {
        return new SkuResponse(
                sku.getId(),
                sku.getProduct().getId(),
                sku.getSkuCode(),
                sku.getColor(),
                sku.getSize(),
                sku.getPrice(),
                sku.getStatus()
        );
    }
}