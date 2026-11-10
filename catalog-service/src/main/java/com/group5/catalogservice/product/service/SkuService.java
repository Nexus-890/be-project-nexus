package com.group5.catalogservice.product.service;

import com.group5.catalogservice.product.dto.request.SkuRequest;
import com.group5.catalogservice.product.dto.response.SkuResponse;
import com.group5.catalogservice.product.entity.Product;
import com.group5.catalogservice.product.entity.Skus;
import com.group5.catalogservice.product.enums.ProductStatus;
import com.group5.catalogservice.product.repository.ProductRepository;
import com.group5.catalogservice.product.repository.SkuRepository;
import com.group5.common_security.CurrentUser;
import com.group5.catalogservice.shared.exception.CatalogErrorCode;
import com.group5.common_core.exception.DomainException;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class SkuService {

    private static final String SKU_CODE_PREFIX = "SKU-";
    private static final Pattern SKU_NUMBER = Pattern.compile("\\d{1,9}");

    private final SkuRepository skuRepository;
    private final ProductRepository productRepository;

    public SkuService(
            SkuRepository skuRepository,
            ProductRepository productRepository) {
        this.skuRepository = skuRepository;
        this.productRepository = productRepository;
    }

    // Tạo SKU sau khi kiểm tra quyền sở hữu sản phẩm và giá cả.
    @Transactional
    public SkuResponse create(
            UUID productId,
            SkuRequest request,
            CurrentUser currentUser) {

        // Đảm bảo người dùng có quyền cập nhật sản phẩm.
        requirePrivilege(currentUser, "PRODUCT.UPDATE");

        // Đảm bảo sản phẩm tồn tại và người dùng được quyền quản lý.
        Product product = skuRepository.findProductForSkuCreate(productId)
                .orElseThrow(() -> new DomainException(CatalogErrorCode.PRODUCT_NOT_FOUND));
        checkProductOwner(product, currentUser);

        //Chaeck giá tiền hợp lệ
        validatePrice(request.getPrice());

        //Map dữ liệu qua SKU mới
        Skus sku = new Skus();
        sku.setProduct(product);
        sku.setSkuCode(nextSkuCode(product.getId()));
        sku.setColor(request.getColor().trim());
        sku.setSize(request.getSize().trim());
        sku.setPrice(request.getPrice());

        return toResponse(saveSku(sku));
    }

    // Cập nhật một SKU sau khi xác thực quyền truy cập và dữ liệu đầu vào.
    @Transactional
    public SkuResponse update(
            UUID id,
            SkuRequest request,
            CurrentUser currentUser) {

        requirePrivilege(currentUser, "PRODUCT.UPDATE");

        Skus sku = skuRepository.findById(id)
                .orElseThrow(() -> new DomainException(CatalogErrorCode.SKU_NOT_FOUND));

        findEditableProduct(sku.getProduct().getId(), currentUser);
        validatePrice(request.getPrice());

        //Áp dụng update cho Sku có tồn tại
        sku.setColor(request.getColor().trim());
        sku.setSize(request.getSize().trim());
        sku.setPrice(request.getPrice());

        return toResponse(saveSku(sku));
    }

    // Chỉ đọc vì thao tác này không làm thay đổi dữ liệu.
    @Transactional(readOnly = true)
    public SkuResponse getById(UUID id) {
        Skus sku = skuRepository.findById(id)
                .orElseThrow(() -> new DomainException(CatalogErrorCode.SKU_NOT_FOUND));

        requireVisibleProduct(sku.getProduct(), CatalogErrorCode.SKU_NOT_FOUND);

        return toResponse(sku);
    }

    @Transactional(readOnly = true)
    public List<SkuResponse> getByProductId(UUID productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new DomainException(CatalogErrorCode.PRODUCT_NOT_FOUND));

        requireVisibleProduct(product, CatalogErrorCode.PRODUCT_NOT_FOUND);
        return skuRepository.findAllByProduct_Id(productId).stream()
                .map(this::toResponse)
                .toList();
    }

    private void requireVisibleProduct(Product product, CatalogErrorCode errorCode) {
        if (product.getStatus() != ProductStatus.ACTIVE
                || product.getDeletedAt() != null) {
            throw new DomainException(errorCode);
        }
    }

    private Product findEditableProduct(
            UUID productId,
            CurrentUser currentUser) {

        Product product = productRepository
                .findByIdAndDeletedAtIsNull(productId)
                .orElseThrow(() -> new DomainException(CatalogErrorCode.PRODUCT_NOT_FOUND));

        checkProductOwner(product, currentUser);

        return product;
    }

    private void requirePrivilege(CurrentUser currentUser, String privilege) {
        if (currentUser == null) {
            throw new DomainException(CatalogErrorCode.UNAUTHENTICATED);
        }

        if (!currentUser.hasPrivilege(privilege)) {
            throw new DomainException(CatalogErrorCode.UNAUTHORIZED);
        }
    }

    private void checkProductOwner(
            Product product,
            CurrentUser currentUser) {

        if (currentUser.hasRole("ADMIN")) {
            return;
        }

        if (!currentUser.id().equals(product.getSellerId())) {
            throw new DomainException(CatalogErrorCode.UNAUTHORIZED);
        }
    }

    private String nextSkuCode(UUID productId) {
        String prefix = SKU_CODE_PREFIX + productId.toString().replace("-", "") + "-";
        int last = skuRepository.findSkuCodes(productId, prefix + "%").stream()
                .map(code -> code.substring(prefix.length()))
                .filter(SKU_NUMBER.asMatchPredicate())
                .mapToInt(Integer::parseInt)
                .max()
                .orElse(0);
        return prefix + String.format("%02d", last + 1);
    }

    private void validatePrice(BigDecimal price) {
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new DomainException(CatalogErrorCode.DATA_INVALID);
        }
    }

    private Skus saveSku(Skus sku) {
        try {
            return skuRepository.saveAndFlush(sku);
        } catch (DataIntegrityViolationException e) {
            if (isDuplicateKey(e)) {
                throw new DomainException(CatalogErrorCode.SKU_DUPLICATE);
            }

            throw e;
        }
    }

    private boolean isDuplicateKey(Throwable error) {
        Throwable current = error;

        while (current != null) {
            if (current instanceof SQLException) {
                SQLException sqlException = (SQLException) current;

                if ("23505".equals(sqlException.getSQLState())) {
                    return true;
                }
            }

            current = current.getCause();
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